// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


#ifndef IMPALA_UTIL_RUNTIME_PROFILE_H
#define IMPALA_UTIL_RUNTIME_PROFILE_H

#include <boost/function.hpp>
#include <boost/scoped_ptr.hpp>
#include <boost/thread/mutex.hpp>
#include <iostream>
#include <sys/time.h>
#include <sys/resource.h>

#include "common/atomic.h"
#include "common/logging.h"
#include "common/object-pool.h"
#include "util/thread.h"
#include "util/stopwatch.h"
#include "util/streaming-sampler.h"
#include "gen-cpp/RuntimeProfile_types.h"

namespace impala {

// Define macros for updating counters.  The macros make it very easy to disable
// all counters at compile time.  Set this to 0 to remove counters.  This is useful
// to do to make sure the counters aren't affecting the system.
#define ENABLE_COUNTERS 1

// Some macro magic to generate unique ids using __COUNTER__
#define CONCAT_IMPL(x, y) x##y
#define MACRO_CONCAT(x, y) CONCAT_IMPL(x, y)

#if ENABLE_COUNTERS
  #define ADD_COUNTER(profile, name, type) (profile)->AddCounter(name, type)
  #define ADD_TIME_SERIES_COUNTER(profile, name, src_counter) \
      (profile)->AddTimeSeriesCounter(name, src_counter)
  #define ADD_TIMER(profile, name) (profile)->AddCounter(name, TCounterType::TIME_NS)
  #define ADD_CHILD_TIMER(profile, name, parent) \
      (profile)->AddCounter(name, TCounterType::TIME_NS, parent)
  #define SCOPED_TIMER(c) \
      ScopedTimer<MonotonicStopWatch> MACRO_CONCAT(SCOPED_TIMER, __COUNTER__)(c)
  #define COUNTER_UPDATE(c, v) (c)->Update(v)
  #define COUNTER_SET(c, v) (c)->Set(v)
  #define ADD_THREAD_COUNTERS(profile, prefix) (profile)->AddThreadCounters(prefix)
  #define SCOPED_THREAD_COUNTER_MEASUREMENT(c) \
    ThreadCounterMeasurement \
      MACRO_CONCAT(SCOPED_THREAD_COUNTER_MEASUREMENT, __COUNTER__)(c)
#else
  #define ADD_COUNTER(profile, name, type) NULL
  #define ADD_TIME_SERIES_COUNTER(profile, name, src_counter) NULL
  #define ADD_TIMER(profile, name) NULL
  #define ADD_CHILD_TIMER(profile, name, parent) NULL
  #define SCOPED_TIMER(c)
  #define COUNTER_UPDATE(c, v)
  #define COUNTER_SET(c, v)
  #define ADD_THREAD_COUNTERS(profile, prefix) NULL
  #define SCOPED_THREAD_COUNTER_MEASUREMENT(c)
#endif

class ObjectPool;

// Runtime profile is a group of profiling counters.  It supports adding named counters
// and being able to serialize and deserialize them.
// The profiles support a tree structure to form a hierarchy of counters.
// Runtime profiles supports measuring wall clock rate based counters.  There is a
// single thread per process that will convert an amount (i.e. bytes) counter to a
// corresponding rate based counter.  This thread wakes up at fixed intervals and updates
// all of the rate counters.
// Thread-safe.
class RuntimeProfile {
 public:
  class Counter {
   public:
    Counter(TCounterType::type type, int64_t value = 0) :
      value_(value),
      type_(type) {
    }
    virtual ~Counter(){}

    virtual void Update(int64_t delta) {
      value_ += delta;
    }

    // Use this to update if the counter is a bitmap
    void BitOr(int64_t delta) {
      value_ |= delta;
    }

    virtual void Set(int64_t value) { value_ = value; }

    virtual void Set(double value) {
      value_ = *reinterpret_cast<int64_t*>(&value);
    }

    virtual int64_t value() const { return value_; }

    virtual double double_value() const {
      return *reinterpret_cast<const double*>(&value_);
    }

    TCounterType::type type() const { return type_; }

   protected:
    friend class RuntimeProfile;

    AtomicInt<int64_t> value_;
    TCounterType::type type_;
  };

  // A counter that keeps track of the highest value seen (reporting that
  // as value()) and the current value.
  class HighWaterMarkCounter : public Counter {
   public:
    HighWaterMarkCounter(TCounterType::type type) : Counter(type) {}

    virtual void Update(int64_t delta) {
      int64_t new_val = current_value_.UpdateAndFetch(delta);
      value_.UpdateMax(new_val);
    }

    // Tries to update the current value by delta. If current_value() + delta
    // exceeds max, return false and current_value is not changed.
    bool TryUpdate(int64_t delta, int64_t max) {
      while (true) {
        int64_t old_val = current_value_;
        int64_t new_val = old_val + delta;
        if (new_val > max) return false;
        if (LIKELY(current_value_.Swap(old_val, new_val))) {
          value_.UpdateMax(new_val);
          return true;
        }
      }
    }

    virtual void Set(int64_t v) {
      current_value_ = v;
      value_.UpdateMax(v);
    }

    int64_t current_value() const { return current_value_; }

   private:
    // The current value of the counter. value_ in the super class represents
    // the high water mark.
    AtomicInt<int64_t> current_value_;
  };

  typedef boost::function<int64_t ()> DerivedCounterFunction;

  // A DerivedCounter also has a name and type, but the value is computed.
  // Do not call Set() and Update().
  class DerivedCounter : public Counter {
   public:
    DerivedCounter(TCounterType::type type, const DerivedCounterFunction& counter_fn)
      : Counter(type),
        counter_fn_(counter_fn) {}

    virtual int64_t value() const {
      return counter_fn_();
    }

   private:
    DerivedCounterFunction counter_fn_;
  };

  // A set of counters that measure thread info, such as total time, user time, sys time.
  class ThreadCounters {
   private:
    friend class ThreadCounterMeasurement;
    friend class RuntimeProfile;

    Counter* total_time_; // total wall clock time
    Counter* user_time_;  // user CPU time
    Counter* sys_time_;   // system CPU time

    // The number of times a context switch resulted due to a process voluntarily giving
    // up the processor before its time slice was completed.
    Counter* voluntary_context_switches_;

    // The number of times a context switch resulted due to a higher priority process
    // becoming runnable or because the current process exceeded its time slice.
    Counter* involuntary_context_switches_;
  };

  // An EventSequence captures a sequence of events (each added by
  // calling MarkEvent). Each event has a text label, and a time
  // (measured relative to the moment Start() was called as t=0). It is
  // useful for tracking the evolution of some serial process, such as
  // the query lifecycle.
  // Not thread-safe.
  class EventSequence {
   public:
    EventSequence() { }

    // Helper constructor for building from Thrift
    EventSequence(const std::vector<int64_t>& timestamps,
                  const std::vector<std::string>& labels) {
      DCHECK(timestamps.size() == labels.size());
      for (int i = 0; i < timestamps.size(); ++i) {
        events_.push_back(make_pair(labels[i], timestamps[i]));
      }
    }

    // Starts the timer without resetting it.
    void Start() { sw_.Start(); }

    // Stops (or effectively pauses) the timer.
    void Stop() { sw_.Stop(); }

    // Stores an event in sequence with the given label and the
    // current time (relative to the first time Start() was called) as
    // the timestamp.
    void MarkEvent(const std::string& label) {
      events_.push_back(make_pair(label, sw_.ElapsedTime()));
    }

    int64_t ElapsedTime() { return sw_.ElapsedTime(); }

    // An Event is a <label, timestamp> pair
    typedef std::pair<std::string, int64_t> Event;

    // An EventList is a sequence of Events, in increasing timestamp order
    typedef std::vector<Event> EventList;

    const EventList& events() const { return events_; }

   private:
    // Stored in increasing time order
    EventList events_;

    // Timer which allows events to be timestamped when they are recorded.
    MonotonicStopWatch sw_;
  };

  typedef StreamingSampler<int64_t, 64> StreamingCounterSampler;
  class TimeSeriesCounter {
   public:
    std::string DebugString() const;

    void AddSample(int ms_elapsed) {
      int64_t sample = sample_fn_();
      samples_.AddSample(sample, ms_elapsed);
    }

   private:
    friend class RuntimeProfile;

    TimeSeriesCounter(const std::string& name, TCounterType::type type,
        DerivedCounterFunction fn)
      : name_(name), type_(type), sample_fn_(fn) {
    }

    // Construct a time series object from existing sample data. This counter
    // is then read-only (i.e. there is no sample function).
    TimeSeriesCounter(const std::string& name, TCounterType::type type, int period,
        const std::vector<int64_t>& values)
      : name_(name), type_(type), sample_fn_(NULL), samples_(period, values) {
    }

    void ToThrift(TTimeSeriesCounter* counter);

    std::string name_;
    TCounterType::type type_;
    DerivedCounterFunction sample_fn_;
    StreamingCounterSampler samples_;
  };

  // Create a runtime profile object with 'name'.  Counters and merged profile are
  // allocated from pool.
  RuntimeProfile(ObjectPool* pool, const std::string& name);

  ~RuntimeProfile();

  // Deserialize from thrift.  Runtime profiles are allocated from the pool.
  static RuntimeProfile* CreateFromThrift(ObjectPool* pool,
      const TRuntimeProfileTree& profiles);

  // Adds a child profile.  This is thread safe.
  // 'indent' indicates whether the child will be printed w/ extra indentation
  // relative to the parent.
  // If location is non-null, child will be inserted after location.  Location must
  // already be added to the profile.
  void AddChild(RuntimeProfile* child,
      bool indent = true, RuntimeProfile* location = NULL);

  // Sorts all children according to a custom comparator. Does not
  // invalidate pointers to profiles.
  template <class Compare>
  void SortChildren(const Compare& cmp) {
    boost::lock_guard<boost::mutex> l(children_lock_);
    std::sort(children_.begin(), children_.end(), cmp);
  }

  // Merges the src profile into this one, combining counters that have an identical
  // path. Info strings from profiles are not merged. 'src' would be a const if it
  // weren't for locking.
  // Calling this concurrently on two RuntimeProfiles in reverse order results in
  // undefined behavior.
  // TODO: Event sequences are ignored
  void Merge(RuntimeProfile* src);

  // Updates this profile w/ the thrift profile: behaves like Merge(), except
  // that existing counters are updated rather than added up.
  // Info strings matched up by key and are updated or added, depending on whether
  // the key has already been registered.
  // TODO: Event sequences are ignored
  void Update(const TRuntimeProfileTree& thrift_profile);

  // Add a counter with 'name'/'type'.  Returns a counter object that the caller can
  // update.  The counter is owned by the RuntimeProfile object.
  // If parent_counter_name is a non-empty string, the counter is added as a child of
  // parent_counter_name.
  // If the counter already exists, the existing counter object is returned.
  Counter* AddCounter(const std::string& name, TCounterType::type type,
      const std::string& parent_counter_name = "");

  // Adds a high water mark counter to the runtime profile. Otherwise, same behavior
  // as AddCounter()
  HighWaterMarkCounter* AddHighWaterMarkCounter(const std::string& name,
      TCounterType::type type, const std::string& parent_counter_name = "");

  // Add a derived counter with 'name'/'type'. The counter is owned by the
  // RuntimeProfile object.
  // If parent_counter_name is a non-empty string, the counter is added as a child of
  // parent_counter_name.
  // Returns NULL if the counter already exists.
  DerivedCounter* AddDerivedCounter(const std::string& name, TCounterType::type type,
      const DerivedCounterFunction& counter_fn,
      const std::string& parent_counter_name = "");

  // Add a set of thread counters prefixed with 'prefix'. Returns a ThreadCounters object
  // that the caller can update.  The counter is owned by the RuntimeProfile object.
  ThreadCounters* AddThreadCounters(const std::string& prefix);

  // Gets the counter object with 'name'.  Returns NULL if there is no counter with
  // that name.
  Counter* GetCounter(const std::string& name);

  // Adds all counters with 'name' that are registered either in this or
  // in any of the child profiles to 'counters'.
  void GetCounters(const std::string& name, std::vector<Counter*>* counters);

  // Adds a string to the runtime profile.  If a value already exists for 'key',
  // the value will be updated.
  void AddInfoString(const std::string& key, const std::string& value);

  // Creates and returns a new EventSequence (owned by the runtime
  // profile) - unless a timer with the same 'key' already exists, in
  // which case it is returned.
  // TODO: EventSequences are not merged by Merge() or Update()
  EventSequence* AddEventSequence(const std::string& key);

  // Returns event sequence with the provided name if it exists, otherwise NULL.
  EventSequence* GetEventSequence(const std::string& name) const;

  // Returns a pointer to the info string value for 'key'.  Returns NULL if
  // the key does not exist.
  const std::string* GetInfoString(const std::string& key);

  // Returns the counter for the total elapsed time.
  Counter* total_time_counter() { return &counter_total_time_; }

  // Prints the counters in a name: value format.
  // Does not hold locks when it makes any function calls.
  void PrettyPrint(std::ostream* s, const std::string& prefix="") const;

  // Serializes profile to thrift.
  // Does not hold locks when it makes any function calls.
  void ToThrift(TRuntimeProfileTree* tree) const;
  void ToThrift(std::vector<TRuntimeProfileNode>* nodes) const;

  // Serializes the runtime profile to a string.  This first serializes the
  // object using thrift compact binary format, then gzip compresses it and
  // finally encodes it as base64.  This is not a lightweight operation and
  // should not be in the hot path.
  std::string SerializeToArchiveString() const;
  void SerializeToArchiveString(std::stringstream* out) const;

  // Divides all counters by n
  void Divide(int n);

  void GetChildren(std::vector<RuntimeProfile*>* children);

  // Gets all profiles in tree, including this one.
  void GetAllChildren(std::vector<RuntimeProfile*>* children);

  // Returns the number of counters in this profile
  int num_counters() const { return counter_map_.size(); }

  // Returns name of this profile
  const std::string& name() const { return name_; }

  // *only call this on top-level profiles*
  // (because it doesn't re-file child profiles)
  void set_name(const std::string& name) { name_ = name; }

  int64_t metadata() const { return metadata_; }
  void set_metadata(int64_t md) { metadata_ = md; }

  // Derived counter function: return measured throughput as input_value/second.
  static int64_t UnitsPerSecond(
      const Counter* total_counter, const Counter* timer);

  // Derived counter function: return aggregated value
  static int64_t CounterSum(const std::vector<Counter*>* counters);

  // Add a rate counter to the current profile based on src_counter with name.
  // The rate counter is updated periodically based on the src counter.
  // The rate counter has units in src_counter unit per second.
  // Rate counters should be stopped (by calling PeriodicCounterUpdater::StopRateCounter)
  // as soon as the src_counter stops changing.
  Counter* AddRateCounter(const std::string& name, Counter* src_counter);

  // Same as 'AddRateCounter' above except values are taken by calling fn.
  // The resulting counter will be of 'type'.
  Counter* AddRateCounter(const std::string& name, DerivedCounterFunction fn,
      TCounterType::type type);

  // Add a sampling counter to the current profile based on src_counter with name.
  // The sampling counter is updated periodically based on the src counter by averaging
  // the samples taken from the src counter.
  // The sampling counter has the same unit as src_counter unit.
  // Sampling counters should be stopped (by calling
  // PeriodicCounterUpdater::StopSamplingCounter) as soon as the src_counter stops
  // changing.
  Counter* AddSamplingCounter(const std::string& name, Counter* src_counter);

  // Same as 'AddSamplingCounter' above except the samples are taken by calling fn.
  Counter* AddSamplingCounter(const std::string& name, DerivedCounterFunction fn);

  // Register a bucket of counters to store the sampled value of src_counter.
  // The src_counter is sampled periodically and the buckets are updated.
  void RegisterBucketingCounters(Counter* src_counter, std::vector<Counter*>* buckets);

  // Create a time series counter. This begins sampling immediately. This counter
  // contains a number of samples that are collected periodically by calling sample_fn().
  // Note: these counters don't get merged (to make average profiles)
  TimeSeriesCounter* AddTimeSeriesCounter(const std::string& name,
      TCounterType::type type, DerivedCounterFunction sample_fn);

  // Create a time series counter that samples the source counter. Sampling begins
  // immediately.
  // Note: these counters don't get merged (to make average profiles)
  TimeSeriesCounter* AddTimeSeriesCounter(const std::string& name, Counter* src_counter);

  // Recursively compute the fraction of the 'total_time' spent in this profile and
  // its children.
  // This function updates local_time_percent_ for each profile.
  void ComputeTimeInProfile();

 private:
  // Pool for allocated counters. Usually owned by the creator of this
  // object, but occasionally allocated in the constructor.
  ObjectPool* pool_;

  // True if we have to delete the pool_ on destruction.
  bool own_pool_;

  // Name for this runtime profile.
  std::string name_;

  // user-supplied, uninterpreted metadata.
  int64_t metadata_;

  // Map from counter names to counters.  The profile owns the memory for the
  // counters.
  typedef std::map<std::string, Counter*> CounterMap;
  CounterMap counter_map_;

  // Map from parent counter name to a set of child counter name.
  // All top level counters are the child of "" (root).
  typedef std::map<std::string, std::set<std::string> > ChildCounterMap;
  ChildCounterMap child_counter_map_;

  // A set of bucket counters registered in this runtime profile.
  std::set<std::vector<Counter*>* > bucketing_counters_;

  // protects counter_map_, counter_child_map_ and bucketing_counters_
  mutable boost::mutex counter_map_lock_;

  // Child profiles.  Does not own memory.
  // We record children in both a map (to facilitate updates) and a vector
  // (to print things in the order they were registered)
  typedef std::map<std::string, RuntimeProfile*> ChildMap;
  ChildMap child_map_;
  // vector of (profile, indentation flag)
  typedef std::vector<std::pair<RuntimeProfile*, bool> > ChildVector;
  ChildVector children_;
  mutable boost::mutex children_lock_;  // protects child_map_ and children_

  typedef std::map<std::string, std::string> InfoStrings;
  InfoStrings info_strings_;

  // Keeps track of the order in which InfoStrings are displayed when printed
  typedef std::vector<std::string> InfoStringsDisplayOrder;
  InfoStringsDisplayOrder info_strings_display_order_;

  // Protects info_strings_ and info_strings_display_order_
  mutable boost::mutex info_strings_lock_;

  typedef std::map<std::string, EventSequence*> EventSequenceMap;
  EventSequenceMap event_sequence_map_;
  mutable boost::mutex event_sequence_lock_;

  typedef std::map<std::string, TimeSeriesCounter*> TimeSeriesCounterMap;
  TimeSeriesCounterMap time_series_counter_map_;
  mutable boost::mutex time_series_counter_map_lock_;

  Counter counter_total_time_;
  // Time spent in just in this profile (i.e. not the children) as a fraction
  // of the total time in the entire profile tree.
  double local_time_percent_;

  // Update a subtree of profiles from nodes, rooted at *idx.
  // On return, *idx points to the node immediately following this subtree.
  void Update(const std::vector<TRuntimeProfileNode>& nodes, int* idx);

  // Helper function to compute compute the fraction of the total time spent in
  // this profile and its children.
  // Called recusively.
  void ComputeTimeInProfile(int64_t total_time);

  // Create a subtree of runtime profiles from nodes, starting at *node_idx.
  // On return, *node_idx is the index one past the end of this subtree
  static RuntimeProfile* CreateFromThrift(ObjectPool* pool,
      const std::vector<TRuntimeProfileNode>& nodes, int* node_idx);

  // Print the child counters of the given counter name
  static void PrintChildCounters(const std::string& prefix,
      const std::string& counter_name, const CounterMap& counter_map,
      const ChildCounterMap& child_counter_map, std::ostream* s);
};

// Utility class to update the counter at object construction and destruction.
// When the object is constructed, decrement the counter by val.
// When the object goes out of scope, increment the counter by val.
class ScopedCounter {
 public:
  ScopedCounter(RuntimeProfile::Counter* counter, int64_t val) :
    val_(val),
    counter_(counter) {
    if (counter == NULL) return;
    counter_->Update(-1L * val_);
  }

  // Increment the counter when object is destroyed
  ~ScopedCounter() {
    if (counter_ != NULL) counter_->Update(val_);
  }

 private:
  // Disable copy constructor and assignment
  ScopedCounter(const ScopedCounter& counter);
  ScopedCounter& operator=(const ScopedCounter& counter);

  int64_t val_;
  RuntimeProfile::Counter* counter_;
};

// Utility class to update time elapsed when the object goes out of scope.
// 'T' must implement the StopWatch "interface" (Start,Stop,ElapsedTime) but
// we use templates not to pay for virtual function overhead.
template<class T>
class ScopedTimer {
 public:
  ScopedTimer(RuntimeProfile::Counter* counter) :
    counter_(counter) {
    if (counter == NULL) return;
    DCHECK(counter->type() == TCounterType::TIME_NS);
    sw_.Start();
  }

  void Stop() { sw_.Stop(); }
  void Start() { sw_.Start(); }

  void UpdateCounter() {
    if (counter_ != NULL) {
      counter_->Update(sw_.ElapsedTime());
    }
  }

  // Updates the underlying counter for the final time and clears the pointer to it.
  void ReleaseCounter() {
    UpdateCounter();
    counter_ = NULL;
  }

  // Update counter when object is destroyed
  ~ScopedTimer() {
    sw_.Stop();
    UpdateCounter();
  }

 private:
  // Disable copy constructor and assignment
  ScopedTimer(const ScopedTimer& timer);
  ScopedTimer& operator=(const ScopedTimer& timer);

  T sw_;
  RuntimeProfile::Counter* counter_;
};

// Utility class to update ThreadCounter when the object goes out of scope or when Stop is
// called. Threads measurements will then be taken using getrusage.
// This is ~5x slower than ScopedTimer due to calling getrusage.
class ThreadCounterMeasurement {
 public:
  ThreadCounterMeasurement(RuntimeProfile::ThreadCounters* counters) :
    stop_(false), counters_(counters) {
    DCHECK(counters != NULL);
    sw_.Start();
    int ret = getrusage(RUSAGE_THREAD, &usage_base_);
    DCHECK_EQ(ret, 0);
  }

  // Stop and update the counter
  void Stop() {
    if (stop_) return;
    stop_ = true;
    sw_.Stop();
    rusage usage;
    int ret = getrusage(RUSAGE_THREAD, &usage);
    DCHECK_EQ(ret, 0);
    int64_t utime_diff =
        (usage.ru_utime.tv_sec - usage_base_.ru_utime.tv_sec) * 1000L * 1000L * 1000L +
        (usage.ru_utime.tv_usec - usage_base_.ru_utime.tv_usec) * 1000L;
    int64_t stime_diff =
        (usage.ru_stime.tv_sec - usage_base_.ru_stime.tv_sec) * 1000L * 1000L * 1000L +
        (usage.ru_stime.tv_usec - usage_base_.ru_stime.tv_usec) * 1000L;
    counters_->total_time_->Update(sw_.ElapsedTime());
    counters_->user_time_->Update(utime_diff);
    counters_->sys_time_->Update(stime_diff);
    counters_->voluntary_context_switches_->Update(usage.ru_nvcsw - usage_base_.ru_nvcsw);
    counters_->involuntary_context_switches_->Update(
        usage.ru_nivcsw - usage_base_.ru_nivcsw);
  }

  // Update counter when object is destroyed
  ~ThreadCounterMeasurement() {
    Stop();
  }

 private:
  // Disable copy constructor and assignment
  ThreadCounterMeasurement(const ThreadCounterMeasurement& timer);
  ThreadCounterMeasurement& operator=(const ThreadCounterMeasurement& timer);

  bool stop_;
  rusage usage_base_;
  MonotonicStopWatch sw_;
  RuntimeProfile::ThreadCounters* counters_;
};

}

#endif
