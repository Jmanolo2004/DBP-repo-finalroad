package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Una cuenta de servicio mal pegada en Render no debe tumbar el backend: solo apaga Google Wallet. */
class GoogleWalletClientConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://walletobjects.googleapis.com/walletobjects/v1";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";

    private static GoogleWalletClient client(String issuer, String account) {
        return new GoogleWalletClient(issuer, account, API, TOKEN, MAPPER, Clock.systemUTC());
    }

    private static String validAccount() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return MAPPER.writeValueAsString(Map.of("type", "service_account", "client_email", "wallet@prueba.iam.gserviceaccount.com", "private_key", pem));
    }

    @Test
    void conLaCuentaCorrectaQuedaConfigurado() throws Exception {
        String account = validAccount();
        assertThat(client("3388000000012345", account).configured()).isTrue();
        // Comillas alrededor, como a veces queda al copiarlo de otro panel.
        assertThat(client("3388000000012345", "\"" + account + "\"").configured()).isTrue();
        assertThat(client("3388000000012345", "  '" + account + "'  ").configured()).isTrue();
    }

    @Test
    void conValoresMalPegadosArrancaSinGoogleWallet() throws Exception {
        String account = validAccount();
        assertThat(client("3388000000012345", account.substring(0, account.length() / 2)).configured()).isFalse(); // cortado
        assertThat(client("3388000000012345", "••••••••").configured()).isFalse(); // lo que muestra un panel que oculta el valor
        assertThat(client("3388000000012345", "{\"type\":\"service_account\"}").configured()).isFalse(); // sin client_email
        assertThat(client("3388000000012345", "{\"client_email\":\"a@b.com\",\"private_key\":\"no-es-una-llave\"}").configured()).isFalse();
        assertThat(client("mi-emisor", account).configured()).isFalse(); // el ID de emisor son solo números
        GoogleWalletClient off = client("", account);
        assertThat(off.configured()).isFalse();
        assertThat(off.issuerId()).isEmpty();
    }

    @Test
    void quitaSoloLasComillasDeAlrededor() {
        assertThat(GoogleWalletClient.unquote(" \"{\\\"a\\\":1}\" ")).isEqualTo("{\\\"a\\\":1}");
        assertThat(GoogleWalletClient.unquote("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(GoogleWalletClient.unquote("\"hola\"")).isEqualTo("\"hola\"");
    }
}
