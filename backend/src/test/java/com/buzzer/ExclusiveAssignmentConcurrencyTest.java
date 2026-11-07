package com.buzzer;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.support.ApiClient;
import com.buzzer.support.FlowHelper;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Critical rule: many partners may quote, only ONE may hold the active assignment - even under concurrent
 * attempts and duplicate retries. Stale acceptance returns REQUEST_ALREADY_ASSIGNED.
 */
@AutoConfigureMockMvc
class ExclusiveAssignmentConcurrencyTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AssignmentService assignments;

    @Test
    void simultaneousAssignmentAttemptsProduceExactlyOneWinner() throws Exception {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(3, true);
        UUID requestId = UUID.fromString(p.requestId());

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID quotation = UUID.fromString(p.quotationIds().get(i % 3));  // three different partners competing
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    assignments.assignExclusive(requestId, quotation, null, "SYSTEM", "concurrency test");
                    winners.incrementAndGet();
                } catch (BusinessException e) {
                    errors.add(e.code().name());
                } catch (Exception e) {
                    errors.add("UNEXPECTED:" + e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(winners.get()).isEqualTo(1);
        assertThat(errors).hasSize(threads - 1);
        // Losers either see the live assignment (REQUEST_ALREADY_ASSIGNED) or that the request already moved on.
        assertThat(errors).allMatch(e -> e.equals("REQUEST_ALREADY_ASSIGNED") || e.equals("INVALID_STATE_TRANSITION"));
        Integer live = jdbc.sql("SELECT count(*) FROM assignments WHERE request_id = ? AND status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')")
                .param(requestId).query(Integer.class).single();
        assertThat(live).isEqualTo(1);
        Integer accepted = jdbc.sql("SELECT count(*) FROM quotations WHERE request_id = ? AND status = 'ACCEPTED'").param(requestId).query(Integer.class).single();
        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void staleAcceptanceAfterAssignmentReturnsControlledError() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(2, true);
        var first = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(first.status()).isEqualTo(200);
        // Customer double-taps a different quote with a new key: the request has moved on.
        var stale = api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(1) + "/accept",
                p.customerToken(), Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(stale.status()).isEqualTo(409);
        // A late system/agent attempt gets REQUEST_ALREADY_ASSIGNED.
        assertThatThrownBy(() -> assignments.assignExclusive(UUID.fromString(p.requestId()), UUID.fromString(p.quotationIds().get(1)), null, "SYSTEM", "late"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.REQUEST_ALREADY_ASSIGNED);
        // The losing partner's invitation is closed.
        String status = jdbc.sql("SELECT i.status FROM partner_invitations i JOIN quotations q ON q.invitation_id = i.id WHERE q.id = ?::uuid")
                .param(p.quotationIds().get(1)).query(String.class).single();
        assertThat(status).isEqualTo("CLOSED");
    }

    @Test
    void declinedAssignmentCanBeReassignedWithNewIdPreservingHistory() {
        ApiClient api = new ApiClient(mvc, mapper);
        FlowHelper.Prepared p = new FlowHelper(api, jdbc).toCustomerApproval(2, true);
        api.post("/api/v1/customer/requests/" + p.requestId() + "/quotations/" + p.quotationIds().get(0) + "/accept",
                p.customerToken(), Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        String first = jdbc.sql("SELECT id::text FROM assignments WHERE request_id = ?::uuid").param(p.requestId()).query(String.class).single();
        String partner = api.login("9000000021", "PARTNER");
        assertThat(api.post("/api/v1/partner/assignments/" + first + "/decline", partner, Map.of()).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/partner/assignments/" + first + "/decline", partner, Map.of("reason", "Not available today")).status()).isEqualTo(200);

        var re = api.post("/api/v1/agent/requests/" + p.requestId() + "/reassign", p.agentToken(),
                Map.of("quotation_id", p.quotationIds().get(1), "reason", "First partner declined"));
        assertThat(re.status()).isEqualTo(200);
        assertThat(re.data().path("id").asText()).isNotEqualTo(first);
        List<String> statuses = jdbc.sql("SELECT status FROM assignments WHERE request_id = ?::uuid ORDER BY created_at").param(p.requestId())
                .query(String.class).list();
        assertThat(statuses).containsExactly("DECLINED", "ACTIVE");
    }
}
