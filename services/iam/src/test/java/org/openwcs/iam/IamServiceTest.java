package org.openwcs.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openwcs.iam.api.Requests;
import org.openwcs.iam.api.UserView;
import org.openwcs.iam.service.IamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots IAM against PostgreSQL 16 (Flyway seeds the roles). Verifies the seeded roles,
 * effective-permission resolution through assigned roles, and catalog validation.
 */
@SpringBootTest
@Testcontainers
class IamServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    IamService iam;

    @Test
    void seededRolesCarryPermissions() {
        assertThat(iam.getRole("VIEWER").permissions()).containsExactlyInAnyOrder(
                "MASTER_DATA_VIEW", "INVENTORY_VIEW", "ORDER_VIEW", "TXLOG_VIEW", "DEVICE_VIEW");
        assertThat(iam.getRole("ADMIN").permissions()).contains("IAM_ADMIN");
    }

    @Test
    void effectivePermissionsAreUnionOfRoles() {
        iam.createUser(new Requests.CreateUser("alice", "Alice", "kc-sub-1"));
        UserView user = iam.setUserRoles("alice", new Requests.SetRoles(Set.of("OPERATOR")));

        assertThat(user.roles()).containsExactly("OPERATOR");
        assertThat(iam.effectivePermissions("alice"))
                .contains("ORDER_POST_TRANSACTION", "STOCK_ADJUST", "INVENTORY_VIEW")
                .doesNotContain("IAM_ADMIN");
    }

    @Test
    void unknownPermissionCodeIsRejected() {
        assertThatThrownBy(() ->
                iam.createRole(new Requests.CreateRole("CUSTOM", "x", Set.of("NOT_A_PERMISSION"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
