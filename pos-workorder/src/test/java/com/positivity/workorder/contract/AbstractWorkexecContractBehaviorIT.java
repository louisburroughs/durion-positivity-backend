package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

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
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared MockMvc setup and persistence cleanup for CAP-121 workexec contract tests.
 */
abstract class AbstractWorkexecContractBehaviorIT {

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
                        var authentication = new UsernamePasswordAuthenticationToken(
                                "workorder-test-user", null, TEST_AUTHORITIES);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        filterChain.doFilter(request, response);
                    }
                })
                .build();
    }

    @AfterEach
    void tearDownBaseContractTest() {
        laborEntryRepository.deleteAll();
        technicianAssignmentRepository.deleteAll();
        workorderRepository.deleteAll();
    }

    protected Workorder seedWorkorder(UUID locationId, WorkorderStatus status) {
        Workorder workorder = Workorder.builder()
                .shopId(locationId)
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .status(status)
                .isReopened(false)
                .build();
        return workorderRepository.save(workorder);
    }

    protected Workorder seedWorkorderInProgress() {
        return seedWorkorder(UUID.randomUUID(), WorkorderStatus.WORK_IN_PROGRESS);
    }
}
