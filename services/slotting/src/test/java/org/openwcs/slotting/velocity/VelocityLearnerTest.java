package org.openwcs.slotting.velocity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.common.EventEnvelope;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the self-taught, recency-weighted ABC velocity end-to-end over a real PostgreSQL 16:
 * movements are fed through the same Map→record decode path the Kafka consumer uses, then the
 * classifier ranks SKUs and writes A/B/C onto storage_profile. Verifies a spiking SKU becomes A,
 * a SKU that goes quiet decays out of A, and that manual_override is respected.
 */
@SpringBootTest
@Testcontainers
class VelocityLearnerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No broker in this test — the projection is driven directly.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    VelocityProjectionService projection;
    @Autowired
    VelocityClassifier classifier;
    @Autowired
    SkuVelocityRepository velocity;
    @Autowired
    StorageProfileRepository profiles;
    @Autowired
    BlockPolicyRepository policies;

    private long seq = 1;

    private EventEnvelope pick(UUID wh, UUID sku, Instant when) {
        return new EventEnvelope(
                UUID.randomUUID(), "stream-1", seq++, VelocityEventTypes.PICKED,
                when, when, "test", null, 1,
                Map.of("warehouseId", wh, "skuId", sku, "qty", new BigDecimal("1")));
    }

    private StorageProfile profile(UUID wh, UUID sku, UUID block, boolean override) {
        StorageProfile p = new StorageProfile();
        p.setWarehouseId(wh);
        p.setSkuId(sku);
        p.setBlockId(block);
        p.setVelocityClass("C");
        p.setManualOverride(override);
        return profiles.save(p);
    }

    @Test
    void spikingSkuBecomesAAndWritesStorageProfile() {
        UUID wh = UUID.randomUUID();
        UUID fast = UUID.randomUUID();
        UUID slow = UUID.randomUUID();
        Instant now = Instant.now();

        StorageProfile fastProfile = profile(wh, fast, UUID.randomUUID(), false);
        profile(wh, slow, UUID.randomUUID(), false);

        // fast SKU spikes with many recent picks; slow SKU barely moves.
        for (int i = 0; i < 20; i++) {
            projection.apply(pick(wh, fast, now));
        }
        projection.apply(pick(wh, slow, now));

        List<SkuVelocity> ranked = classifier.recompute(wh);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getSkuId()).isEqualTo(fast);
        assertThat(ranked.get(0).getVelocityClass()).isEqualTo("A");
        assertThat(velocity.findByWarehouseIdAndSkuId(wh, fast).orElseThrow().getScore())
                .isEqualByComparingTo("20");

        // The learned class is pushed onto the (non-overridden) storage_profile.
        assertThat(profiles.findById(fastProfile.getId()).orElseThrow().getVelocityClass()).isEqualTo("A");
    }

    @Test
    void quietSkuDecaysOutOfClassA() {
        UUID wh = UUID.randomUUID();
        UUID fading = UUID.randomUUID();
        UUID rising = UUID.randomUUID();
        Instant now = Instant.now();

        profile(wh, fading, UUID.randomUUID(), false);
        profile(wh, rising, UUID.randomUUID(), false);

        // First cycle: `fading` is the hot SKU and ranks A.
        for (int i = 0; i < 30; i++) {
            projection.apply(pick(wh, fading, now));
        }
        classifier.recompute(wh);
        assertThat(velocity.findByWarehouseIdAndSkuId(wh, fading).orElseThrow().getVelocityClass())
                .isEqualTo("A");

        // Backdate the decay clock so the next recompute applies several half-lives of decay
        // to the now-quiet `fading` SKU. Default half-life is 14 days.
        SkuVelocity fadingRow = velocity.findByWarehouseIdAndSkuId(wh, fading).orElseThrow();
        BigDecimal beforeDecay = fadingRow.getScore();
        fadingRow.setDecayedAt(now.minus(70, ChronoUnit.DAYS)); // ~5 half-lives
        velocity.save(fadingRow);

        // Meanwhile `rising` spikes hard.
        for (int i = 0; i < 40; i++) {
            projection.apply(pick(wh, rising, now));
        }
        List<SkuVelocity> ranked = classifier.recompute(wh);

        SkuVelocity afterFading = velocity.findByWarehouseIdAndSkuId(wh, fading).orElseThrow();
        assertThat(afterFading.getScore()).isLessThan(beforeDecay); // decayed
        // rising now tops the ranking and takes A; the faded SKU drops below it.
        assertThat(ranked.get(0).getSkuId()).isEqualTo(rising);
        assertThat(ranked.get(0).getVelocityClass()).isEqualTo("A");
        assertThat(afterFading.getVelocityClass()).isNotEqualTo("A");
    }

    @Test
    void manualOverrideIsRespected() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        Instant now = Instant.now();

        StorageProfile pinned = profile(wh, sku, UUID.randomUUID(), true); // operator pinned to C

        for (int i = 0; i < 50; i++) {
            projection.apply(pick(wh, sku, now));
        }
        classifier.recompute(wh);

        // The SKU clearly classifies A, but the override keeps storage_profile at C.
        assertThat(velocity.findByWarehouseIdAndSkuId(wh, sku).orElseThrow().getVelocityClass())
                .isEqualTo("A");
        assertThat(profiles.findById(pinned.getId()).orElseThrow().getVelocityClass()).isEqualTo("C");
    }

    @Test
    void redeliveredEventIsCountedOnce() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        EventEnvelope event = pick(wh, sku, Instant.now());

        projection.apply(event);
        projection.apply(event); // redelivery / replay of the same event_id

        assertThat(velocity.findByWarehouseIdAndSkuId(wh, sku).orElseThrow().getPendingPicks())
                .isEqualByComparingTo("1");
    }

    @Test
    void honoursConfiguredAbcSharesFromBlockPolicy() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        Instant now = Instant.now();

        // Only the top 10% is A, no B — exercise the configurable cutoffs.
        BlockPolicy policy = new BlockPolicy();
        policy.setWarehouseId(wh);
        policy.setBlockId(block);
        policy.setAbcAShare(new BigDecimal("0.1"));
        policy.setAbcBShare(BigDecimal.ZERO);
        policies.save(policy);

        // 10 SKUs each with a distinct, descending pick count so the ranking is unambiguous.
        UUID[] skus = new UUID[10];
        for (int i = 0; i < 10; i++) {
            skus[i] = UUID.randomUUID();
            profile(wh, skus[i], block, false);
            for (int p = 0; p < (10 - i); p++) {
                projection.apply(pick(wh, skus[i], now));
            }
        }

        List<SkuVelocity> ranked = classifier.recompute(wh);

        // ceil(10 * 0.1) = 1 A, 0 B, rest C.
        assertThat(ranked.get(0).getVelocityClass()).isEqualTo("A");
        assertThat(ranked.stream().filter(r -> "A".equals(r.getVelocityClass())).count()).isEqualTo(1);
        assertThat(ranked.stream().filter(r -> "B".equals(r.getVelocityClass())).count()).isZero();
    }
}
