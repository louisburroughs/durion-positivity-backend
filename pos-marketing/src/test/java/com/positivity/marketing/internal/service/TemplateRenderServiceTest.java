package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story #1147: a token typo should cost a validation error, not a batch of messages reading
 * "Hi ," in front of customers.
 */
class TemplateRenderServiceTest {

    private final TemplateRenderService service = new TemplateRenderService();

    @Test
    @DisplayName("accepts tokens available to the audience")
    void acceptsValidTokens() {
        assertThatCode(() -> service.validate(
                        CampaignChannel.EMAIL,
                        AudienceType.COMMERCIAL,
                        "An offer for {{accountName}}",
                        "Hi {{contactFirstName}}, use {{offerCode}}."))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a token that does not exist")
    void rejectsUnknownToken() {
        assertThatThrownBy(() ->
                        service.validate(CampaignChannel.EMAIL, AudienceType.COMMERCIAL, "Hello", "Hi {{firstNam}}"))
                .isInstanceOf(MarketingUnprocessableEntityException.class)
                .hasMessageContaining("firstNam");
    }

    @Test
    @DisplayName("rejects a commercial token in an individual template")
    void rejectsTokenOutsideAudience() {
        assertThatThrownBy(() ->
                        service.validate(CampaignChannel.EMAIL, AudienceType.INDIVIDUAL, "Hello", "Hi {{accountName}}"))
                .isInstanceOf(MarketingUnprocessableEntityException.class)
                .hasMessageContaining("accountName");
    }

    @Test
    @DisplayName("an email needs a subject and an SMS must not have one")
    void enforcesSubjectRules() {
        assertThatThrownBy(() -> service.validate(CampaignChannel.EMAIL, AudienceType.COMMERCIAL, "  ", "Body"))
                .isInstanceOf(MarketingUnprocessableEntityException.class)
                .hasMessageContaining("require a subject");
        assertThatThrownBy(() -> service.validate(CampaignChannel.SMS, AudienceType.COMMERCIAL, "Subject", "Body"))
                .isInstanceOf(MarketingUnprocessableEntityException.class)
                .hasMessageContaining("no subject line");
    }

    @Test
    @DisplayName("rejects an oversized SMS body, which would bill per extra segment per recipient")
    void rejectsOversizedSms() {
        String longBody = "x".repeat(321);

        assertThatThrownBy(() -> service.validate(CampaignChannel.SMS, AudienceType.INDIVIDUAL, null, longBody))
                .isInstanceOf(MarketingUnprocessableEntityException.class)
                .hasMessageContaining("character limit");
    }

    @Test
    @DisplayName("substitutes supplied values and blanks the rest rather than leaking the raw token")
    void rendersTokens() {
        String rendered = service.render(
                "Hi {{contactFirstName}}, code {{campaignCode}}.", Map.of("campaignCode", "SPRING-2026"));

        assertThat(rendered).isEqualTo("Hi , code SPRING-2026.");
    }

    @Test
    @DisplayName("a substituted value containing $ or backslash is inserted literally")
    void rendersSpecialCharactersLiterally() {
        assertThat(service.render("Save {{offerCode}}", Map.of("offerCode", "$5\\off")))
                .isEqualTo("Save $5\\off");
    }
}
