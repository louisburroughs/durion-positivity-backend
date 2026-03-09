package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.positivity.security.common.GatewaySecurityConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared MockMvc setup and persistence cleanup for CAP-121 workexec contract
 * tests.
 */
abstract class AbstractWorkexecContractBehaviorIT {

    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEST_USERNAME = "workorder-test-user";

    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("workorder:labor:view"),
            new SimpleGrantedAuthority("workorder:labor:add"));

    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected WorkorderRepository workorderRepository;

    @Autowired
    protected WorkorderLaborEntryRepository laborEntryRepository;

    @Autowired
    protected TechnicianAssignmentRepository technicianAssignmentRepository;

    @Autowired
    protected IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setUpBaseContractTest() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain filterChain) throws ServletException, IOException {
                        UUID userId = TEST_USER_ID;
                        String userIdHeader = request.getHeader("X-User-Id");
                        if (userIdHeader != null && !userIdHeader.isBlank()) {
                            try {
                                userId = UUID.fromString(userIdHeader);
                            } catch (IllegalArgumentException ignored) {
                                userId = TEST_USER_ID;
                            }
                        }

                        var authentication = new UsernamePasswordAuthenticationToken(
                                TEST_USERNAME, null, TEST_AUTHORITIES);
                        authentication.setDetails(Map.of(
                                GatewaySecurityConstants.DETAIL_USER_ID, userId,
                                GatewaySecurityConstants.DETAIL_USERNAME, TEST_USERNAME));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        filterChain.doFilter(request, response);
                    }
                })
                .build();
    }

    @AfterEach
    void tearDownBaseContractTest() {
        laborEntryRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        technicianAssignmentRepository.deleteAll();
        workorderRepository.deleteAll();
    }

    protected Workorder seedWorkorder(UUID locationId, WorkorderStatus status) {
        Workorder workorder = Workorder.builder()
                .shopId(locationId)
                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .status(status)
                .isReopened(false)
                .build();
        return workorderRepository.save(workorder);
    }

    protected Workorder seedWorkorderInProgress() {
        return seedWorkorder(UUID.fromString("00000000-0000-0000-0000-000000000001"), WorkorderStatus.WORK_IN_PROGRESS);
    }
}
