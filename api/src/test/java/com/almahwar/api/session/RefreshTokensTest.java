package com.almahwar.api.session;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class RefreshTokensTest {
    @Test void tokensHave256RandomBitsCanonicalUrlEncodingAndSha256Only() throws Exception {
        var generated=new HashSet<String>();
        for(int i=0;i<1000;i++) {
            String token=RefreshTokens.generate();
            assertThat(RefreshTokens.validFormat(token)).isTrue();
            assertThat(token).matches("amr_[A-Za-z0-9_-]{43}");
            assertThat(java.util.Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
            assertThat(RefreshTokens.hash(token)).hasSize(32).isEqualTo(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            assertThat(generated.add(token)).isTrue();
        }
        assertThat(RefreshTokens.validFormat(null)).isFalse();
        assertThat(RefreshTokens.validFormat("amr_"+"A".repeat(42)+"B")).isFalse(); // non-canonical trailing bits
    }
}
