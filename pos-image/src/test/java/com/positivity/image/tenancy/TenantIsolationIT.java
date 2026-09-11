package com.positivity.image.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.image.internal.entity.ImageContentEntity;
import com.positivity.image.internal.entity.ImageEntity;
import com.positivity.image.internal.repository.ImageContentRepository;
import com.positivity.image.internal.repository.ImageRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table. The image row is the subject: the one table every read here starts from.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-image)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private ImageRepository rows;

    @Autowired
    private ImageContentRepository content;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        Long id = asTenant(TENANT_A, () -> rows.saveAndFlush(image()).getId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(rows.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(rows.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countById(jdbc, id)).as("owner reads through raw SQL").isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(rows.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countById(jdbc, id)).as("RLS hides it from raw SQL too").isZero();
            assertThat(jdbc.update("UPDATE image SET filename = 'hijacked.png' WHERE id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countById(jdbc, id)).isZero();
        assertThatThrownBy(
                        () -> jdbc.update("INSERT INTO image (id, filename) VALUES (?, 'nobody.png')", 9_000_000_001L))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(rows.findById(id).orElseThrow().getFilename())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("logo.png"));
    }

    /**
     * Two tenants storing byte-identical artwork must each end up with their own readable copy.
     *
     * <p>The baseline keyed {@code image_content} on the content hash alone. Unique and primary key
     * constraints are enforced across every row whatever row-level security hides, so the second
     * tenant's insert collided with a row it could not see; {@code ImageStorageServiceImpl} read
     * that collision as a concurrent store of its own and left the image unreadable ever after.
     * V2 leads the key with {@code tenant_id}, so identical bytes are simply two rows.
     */
    @Test
    void twoTenantsCanEachHoldTheSameContentHash() {
        String sharedHash = "a".repeat(64);

        asTenant(TENANT_A, () -> content.saveAndFlush(content(sharedHash, "A".getBytes(UTF_8))));
        asTenant(TENANT_B, () -> content.saveAndFlush(content(sharedHash, "B".getBytes(UTF_8))));

        asTenant(TENANT_A, () -> {
            ImageContentEntity mine = content.findById(sharedHash).orElseThrow();
            assertThat(mine.getTenantId()).isEqualTo(TENANT_A);
            assertThat(mine.getContent())
                    .as("tenant A reads its own bytes, not the other tenant's")
                    .isEqualTo("A".getBytes(UTF_8));
        });

        asTenant(TENANT_B, () -> {
            ImageContentEntity mine = content.findById(sharedHash).orElseThrow();
            assertThat(mine.getTenantId()).isEqualTo(TENANT_B);
            assertThat(mine.getContent())
                    .as("tenant B reads its own bytes; before V2 this row could not be stored at all")
                    .isEqualTo("B".getBytes(UTF_8));
        });

        // Two distinct rows now share the hash. Each tenant sees exactly its own: RLS scopes what is
        // visible, and the tenant-led primary key is what allows the second row to exist at all.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(
                TENANT_A,
                () -> assertThat(countByHash(jdbc, sharedHash))
                        .as("tenant A sees one row for the shared hash: its own")
                        .isEqualTo(1));
        asTenant(
                TENANT_B,
                () -> assertThat(countByHash(jdbc, sharedHash))
                        .as("tenant B sees one row for the shared hash: its own")
                        .isEqualTo(1));
        assertThat(countByHash(jdbc, sharedHash)).as("unbound, RLS hides both").isZero();
    }

    private static int countByHash(JdbcTemplate jdbc, String contentHash) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM image_content WHERE content_hash = ?", Integer.class, contentHash);
        return count == null ? 0 : count;
    }

    private static ImageContentEntity content(String contentHash, byte[] bytes) {
        ImageContentEntity entity = new ImageContentEntity();
        entity.setContentHash(contentHash);
        entity.setContentType("image/png");
        entity.setByteSize(bytes.length);
        entity.setContent(bytes);
        entity.setCreatedAt(Instant.EPOCH);
        return entity;
    }

    private static ImageEntity image() {
        ImageEntity image = new ImageEntity();
        image.setFilename("logo.png");
        return image;
    }

    private static int countById(JdbcTemplate jdbc, Long id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM image WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
