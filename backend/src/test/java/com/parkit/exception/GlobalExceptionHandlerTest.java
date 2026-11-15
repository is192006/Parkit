package com.parkit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit tests for {@link GlobalExceptionHandler} using a standalone MockMvc
 * (no Spring context, no database).
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    static Stream<Arguments> parkItExceptions() {
        return Stream.of(
                Arguments.of("not-found", 404, "RESOURCE_NOT_FOUND"),
                Arguments.of("rule", 422, "BOOKING_RULE_VIOLATION"),
                Arguments.of("transition", 409, "INVALID_STATE_TRANSITION"),
                Arguments.of("slot", 409, "SLOT_UNAVAILABLE"),
                Arguments.of("concurrency", 409, "CONCURRENCY_CONFLICT"),
                Arguments.of("duplicate", 409, "DUPLICATE_RESOURCE"),
                Arguments.of("hold", 410, "HOLD_EXPIRED"),
                Arguments.of("payment", 402, "PAYMENT_FAILED"),
                Arguments.of("forbidden", 403, "FORBIDDEN_OPERATION"));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @MethodSource("parkItExceptions")
    void mapsEachParkItExceptionToItsStatusAndCode(String kind, int httpStatus, String code) throws Exception {
        mockMvc.perform(get("/test/throw/{kind}", kind))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.errorCode").value(code))
                .andExpect(jsonPath("$.detail").value("message for " + kind));
    }

    @Test
    void invalidRequestBodyReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void validRequestBodyPasses() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"asha@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void exclusionViolationReturns409ConcurrencyConflict() throws Exception {
        mockMvc.perform(get("/test/throw/exclusion"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENCY_CONFLICT"));
    }

    @Test
    void uniqueViolationReturns409Duplicate() throws Exception {
        mockMvc.perform(get("/test/throw/unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternals() throws Exception {
        String body = mockMvc.perform(get("/test/throw/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret internal detail").doesNotContain("IllegalStateException");
    }

    @Test
    void findSqlStateWalksCauseChain() {
        var ex = new DataIntegrityViolationException("x",
                new RuntimeException(new SQLException("boom", "23P01")));
        assertThat(GlobalExceptionHandler.findSqlState(ex)).isEqualTo("23P01");
        assertThat(GlobalExceptionHandler.findSqlState(new RuntimeException())).isNull();
    }

    record SampleRequest(@NotBlank String name, @NotBlank @Email String email) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/throw/{kind}")
        String doThrow(@PathVariable String kind) {
            String m = "message for " + kind;
            throw switch (kind) {
                case "not-found" -> new ResourceNotFoundException(m);
                case "rule" -> new BookingRuleViolationException(m);
                case "transition" -> new InvalidStateTransitionException(m);
                case "slot" -> new SlotUnavailableException(m);
                case "concurrency" -> new ConcurrencyConflictException(m);
                case "duplicate" -> new DuplicateResourceException(m);
                case "hold" -> new HoldExpiredException(m);
                case "payment" -> new PaymentException(m);
                case "forbidden" -> new ForbiddenOperationException(m);
                case "exclusion" -> new DataIntegrityViolationException("x", new SQLException("overlap", "23P01"));
                case "unique" -> new DataIntegrityViolationException("x", new SQLException("dup", "23505"));
                default -> new IllegalStateException("secret internal detail");
            };
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody SampleRequest request) {
            return "ok";
        }
    }
}
