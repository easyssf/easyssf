package org.easyssf.examples.oidcclient;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
class HomeController {

    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    String home(@AuthenticationPrincipal OidcUser user, HttpSession session, CsrfToken csrfToken) {
        String accountConsole = user.getIssuer() + "/account";
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>SSF example OIDC client</title></head>
                <body>
                <h1>Hello %s</h1>
                <table>
                <tr><td>Subject (sub)</td><td><code>%s</code></td></tr>
                <tr><td>Keycloak session (sid)</td><td><code>%s</code></td></tr>
                <tr><td>Local session</td><td><code>%s</code></td></tr>
                </table>
                <p>Sign out in the <a href="%s" target="_blank">Keycloak account console</a>: Keycloak reports
                the revoked session with a security event and the local session is gone. This page checks its
                session every few seconds and whenever the tab becomes visible, and sends you to the login
                once the session has ended.</p>
                <p id="status">Session check: pending</p>
                <form method="post" action="/logout">
                <input type="hidden" name="%s" value="%s">
                <button type="submit">Log out here (and in Keycloak)</button>
                </form>
                <script>
                (function () {
                    const status = document.getElementById("status");
                    let checks = 0;
                    function checkSession() {
                        // GET /auth/check answers 200 as long as the local session exists, 401 once it is gone.
                        // No call to Keycloak is needed: the receiver ends the local session on the event.
                        fetch("/auth/check", { headers: { "Accept": "application/json" }, cache: "no-store",
                                               credentials: "same-origin", redirect: "manual" })
                            .then(function (response) {
                                if (response.status !== 200) {
                                    status.textContent = "Session check: session ended, redirecting to the login";
                                    window.location.reload();
                                    return;
                                }
                                checks++;
                                status.textContent = "Session check " + checks + ": active at "
                                        + new Date().toLocaleTimeString();
                            })
                            .catch(function () {
                                status.textContent = "Session check: application not reachable";
                            });
                    }
                    document.addEventListener("visibilitychange", function () {
                        if (!document.hidden) {
                            checkSession();
                        }
                    });
                    setInterval(checkSession, 5000);
                    checkSession();
                })();
                </script>
                </body>
                </html>
                """.formatted(escape(user.getPreferredUsername()), escape(user.getSubject()),
                escape(user.getClaimAsString("sid")), escape(session.getId()), escape(accountConsole),
                escape(csrfToken.getParameterName()), escape(csrfToken.getToken()));
    }

    private static String escape(String value) {
        return (value != null) ? HtmlUtils.htmlEscape(value) : "";
    }

}
