package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;
import io.nekohasekai.sagernet.aidl.SpeedDisplayData;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  int urlTest();
  int urlTestCustomUrl(String url, int timeoutMs);
  oneway void postNotificationSpeed(in SpeedDisplayData speed);

  // Connections viewer: JSON snapshot from the core's traffic tracker.
  // filter: 1 = active, 2 = closed, 3 = all.
  String queryConnections(int filter);
  boolean closeConnection(String id);
  oneway void closeAllConnections();
}
