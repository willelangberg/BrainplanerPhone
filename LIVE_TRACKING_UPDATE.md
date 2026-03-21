# Phone App Live Tracking Update

## 📱 Changes Made to BrainplanerPhone Android App

### Problem
The Android app was only logging events to Supabase directly and sending aggregate metrics at the end of sessions. It wasn't sending live event streams to the Cloud API for real-time productivity dip detection.

### Solution
Updated `PhoneAwarenessService.kt` to implement **live event batching** and submission to Cloud API.

## 🔧 Technical Changes

### 1. **Added Live Event Batching**
```kotlin
private val liveEventBatch = mutableListOf<Map<String, Any>>()
private val BATCH_SIZE = 10 // Send batch after 10 events
private val BATCH_TIMEOUT_MS = 30000L // Or after 30 seconds
```

### 2. **Refactored `logEvent()` Method**
- **Before**: Only logged to Supabase directly
- **After**: 
  - Queues events in a batch
  - Submits to Cloud API when batch size reaches 10 events OR after 30 seconds
  - Still logs to Supabase as backup

### 3. **New Methods Added**

#### `submitLiveEventBatch()`
Asynchronously submits batched phone events to:
```
POST /sessions/{session_id}/phone-events
```
With payload format:
```json
{
  "events": [
    {
      "event_type": "screen_on|screen_off|unlock",
      "timestamp": "2026-02-03T09:27:15.000Z",
      "device": "phone",
      "metadata": {}
    }
  ]
}
```

#### `submitLiveEventBatchSync()`
Synchronous version called on service destruction to flush remaining events.

#### `logEventToSupabase()`
Separated Supabase logging for backup/debugging purposes.

### 4. **Enhanced Cleanup on Destroy**
```kotlin
override fun onDestroy() {
    // Flush remaining events before shutdown
    if (liveEventBatch.isNotEmpty()) {
        submitLiveEventBatchSync(sessionId, userId)
    }
    // ... rest of cleanup
}
```

## 📊 Event Flow

```
Phone Screen Event
       ↓
   logEvent()
       ↓
   [Queue in batch]
       ↓
   Batch ready? (10 events OR 30s)
       ↓
   Submit to Cloud API: /sessions/{id}/phone-events
       ↓
   Also log to Supabase (backup)
```

## 🎯 Benefits

1. **Real-time Productivity Alerts**: Events now reach Cloud API immediately for dip detection
2. **Efficient Network Usage**: Batching reduces number of HTTP requests
3. **Dual Redundancy**: Events go to both Cloud API (for live analysis) and Supabase (for backup)
4. **No Data Loss**: Flushes remaining events on service shutdown

## 🚀 Testing

### Local Development
Update the Cloud API URL in the code:
```kotlin
private val CLOUD_API_URL = "http://192.168.0.23:8000"  // Your local IP
```

### Production
Uses:
```kotlin
private val CLOUD_API_URL = "https://brainplaner-api-beta.onrender.com"
```

## 📋 Next Steps

1. **Rebuild the Android app** in Android Studio
2. **Install on your phone**
3. **Start a focus session**
4. **Monitor logcat** for these messages:
   - `Queued event: screen_on (batch size: 1)`
   - `✓ Live events batch submitted successfully`
   - `✗ Failed to submit...` (if connection issues)

5. **Check Cloud API logs** to verify events are received

## 🔍 Verification

The Cloud API `/sessions/{session_id}/check-productivity-dip` endpoint will now have access to real-time phone events and can detect when you're getting distracted by your phone during focus sessions!

## 💡 Configuration

Adjust batching behavior if needed:
- `BATCH_SIZE = 10` - Events per batch
- `BATCH_TIMEOUT_MS = 30000L` - Max wait time (30 seconds)

Lower values = more real-time but more network requests
Higher values = fewer requests but slight delay in detection
