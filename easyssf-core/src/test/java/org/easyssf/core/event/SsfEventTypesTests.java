package org.easyssf.core.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SsfEventTypesTests {

    private static final String ACME_LOGIN = "https://events.acme.example/login";

    @Test
    void resolvesBuiltInAliasesAndPassesUrisThrough() {
        assertThat(SsfEventTypes.resolve("CaepSessionRevoked")).isEqualTo(SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(SsfEventTypes.resolve(ACME_LOGIN)).isEqualTo(ACME_LOGIN);
        assertThat(SsfEventTypes.aliasOf(SsfEventTypes.CAEP_SESSION_REVOKED)).isEqualTo("CaepSessionRevoked");
        assertThat(SsfEventTypes.aliasOf(ACME_LOGIN)).isEqualTo(ACME_LOGIN);
        assertThat(SsfEventTypes.isAlias("CaepSessionRevoked")).isTrue();
        assertThat(SsfEventTypes.isAlias(ACME_LOGIN)).isFalse();
    }

    @Test
    void scimEventTypesAreUrnsWithAliases() {
        assertThat(SsfEventTypes.resolve("ScimProvCreateFull"))
            .isEqualTo("urn:ietf:params:scim:event:prov:create:full");
        assertThat(SsfEventTypes.aliasOf(SsfEventTypes.SCIM_PROV_DEACTIVATE)).isEqualTo("ScimProvDeactivate");
        assertThat(SsfEventTypes.aliasOf(SsfEventTypes.SCIM_MISC_ASYNC_RESPONSE)).isEqualTo("ScimMiscAsyncResponse");
        assertThat(SsfEventTypes.aliases()).containsEntry("ScimFeedRemove", SsfEventTypes.SCIM_FEED_REMOVE);
    }

    @Test
    void registeredAliasResolvesLikeABuiltInOne() {
        SsfEventTypes.registerAlias("AcmeLogin", ACME_LOGIN);
        assertThat(SsfEventTypes.resolve("AcmeLogin")).isEqualTo(ACME_LOGIN);
        assertThat(SsfEventTypes.aliasOf(ACME_LOGIN)).isEqualTo("AcmeLogin");
        assertThat(SsfEventTypes.aliases()).containsEntry("AcmeLogin", ACME_LOGIN)
            .containsEntry("CaepSessionRevoked", SsfEventTypes.CAEP_SESSION_REVOKED);
        // registering the same mapping again is fine
        SsfEventTypes.registerAlias("AcmeLogin", ACME_LOGIN);
    }

    @Test
    void aliasCannotBeMappedToAnotherUri() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SsfEventTypes.registerAlias("CaepSessionRevoked", ACME_LOGIN))
            .withMessageContaining("already mapped");
        SsfEventTypes.registerAlias("AcmeLogout", "https://events.acme.example/logout");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SsfEventTypes.registerAlias("AcmeLogout", "https://events.acme.example/other"))
            .withMessageContaining("already mapped");
        assertThat(SsfEventTypes.resolve("CaepSessionRevoked")).isEqualTo(SsfEventTypes.CAEP_SESSION_REVOKED);
    }

    @Test
    void uriMayHaveSeveralAliasesAndKeepsTheFirstForDisplay() {
        SsfEventTypes.registerAlias("SessionRevoked", SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(SsfEventTypes.resolve("SessionRevoked")).isEqualTo(SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(SsfEventTypes.aliasOf(SsfEventTypes.CAEP_SESSION_REVOKED)).isEqualTo("CaepSessionRevoked");
    }

    @Test
    void rejectsMalformedAliasesAndUris() {
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias("", ACME_LOGIN));
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias("urn:x", ACME_LOGIN));
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias("a/b", ACME_LOGIN));
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias(" Acme", ACME_LOGIN));
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias("Acme", "login"));
        assertThatIllegalArgumentException().isThrownBy(() -> SsfEventTypes.registerAlias("Acme", null));
    }

}
