package com.predicta.mg.services.traffic.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.index.strtree.STRtree;

class OsmIndexTest {

  private OsmSnapshot dummySnapshot() {
    STRtree q = new STRtree();
    STRtree r = new STRtree();
    q.build();
    r.build();
    return new OsmSnapshot(q, Map.of(), r, System.currentTimeMillis());
  }

  @Test
  void disabled_rend_toujours_null() {
    OverpassClient client = mock(OverpassClient.class);
    OsmIndex index = new OsmIndex(client, false, 1440, 300);
    assertThat(index.snapshotOrNull()).isNull();
  }

  @Test
  void premier_appel_charge_et_rend_le_snapshot_si_dans_le_timeout() {
    OverpassClient client = mock(OverpassClient.class);
    when(client.load()).thenReturn(dummySnapshot());

    // ready-timeout large -> le 1er appel attend la fin du load de fond.
    OsmIndex index = new OsmIndex(client, true, 1440, 5000);
    assertThat(index.snapshotOrNull()).isNotNull();
  }

  @Test
  void load_qui_echoue_laisse_le_snapshot_null() {
    OverpassClient client = mock(OverpassClient.class);
    when(client.load()).thenThrow(new IllegalStateException("overpass down"));

    OsmIndex index = new OsmIndex(client, true, 1440, 500);
    assertThat(index.snapshotOrNull()).isNull();
    // le load de fond s'est terminé (échec avalé) -> reste null durablement
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(index.snapshotOrNull()).isNull());
  }
}
