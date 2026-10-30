package com.relayagents.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    @Test
    void redactsEmailsPhonesAndLuhnValidCards() {
        PiiRedactor.Result result = PiiRedactor.redact(
            "Mail asha.rao@example.com or call +91 98765 43210. Card 4111 1111 1111 1111 was charged.");

        assertThat(result.text()).isEqualTo("Mail [email] or call [phone]. Card [card] was charged.");
        assertThat(result.redactions()).isEqualTo(3);
    }

    @Test
    void leavesOrderNumbersAndShortNumbersAlone() {
        String text = "Order O-5002 for 1250 units, reference 4111111111111112.";
        PiiRedactor.Result result = PiiRedactor.redact(text);
        assertThat(result.text()).doesNotContain("[card]").contains("O-5002", "1250");
    }
}
