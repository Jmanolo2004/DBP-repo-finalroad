package com.fidegresa.publicmenu;

import com.fidegresa.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Límite de peticiones: IP detrás del proxy y ventana de tiempo. */
class PublicRateLimiterTest {

    @Test
    void usaElUltimoValorDeLaCabeceraDelProxy() {
        PublicRateLimiter limiter = new PublicRateLimiter("X-Forwarded-For", Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7");
        assertThat(limiter.clientIp(request)).isEqualTo("203.0.113.7");
        assertThat(limiter.clientIp(new MockHttpServletRequest())).isEqualTo("127.0.0.1");
        assertThat(new PublicRateLimiter("", Clock.systemUTC()).clientIp(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void laVentanaSeLiberaConElTiempo() {
        MutableClock clock = new MutableClock();
        PublicRateLimiter limiter = new PublicRateLimiter("", clock);
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i < 5; i++) limiter.check(request, PublicRateLimiter.Limit.JOIN);
        assertThatThrownBy(() -> limiter.check(request, PublicRateLimiter.Limit.JOIN)).isInstanceOf(ApiException.class);
        limiter.check(request, PublicRateLimiter.Limit.MENU); // otro límite, otra cuenta
        clock.advance(Duration.ofMinutes(11));
        limiter.check(request, PublicRateLimiter.Limit.JOIN);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-25T15:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("America/Lima");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
