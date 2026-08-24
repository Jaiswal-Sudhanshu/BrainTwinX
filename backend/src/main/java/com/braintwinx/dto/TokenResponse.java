package com.braintwinx.dto;

/**
 * Issued tokens and the authenticated identity.
 *
 * <p>Carries {@code publicId}, never the internal database id (brief section 26). The role is
 * returned so the client can render role-appropriate UI — but that is a usability affordance only:
 * the server re-checks authorisation on every request, and hidden UI is never an access control.
 *
 * @param expiresInSeconds access-token lifetime, so a client can refresh proactively rather than
 *                         waiting for a 401
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        String publicId,
        String username,
        String fullName,
        String role) {

    public static TokenResponse bearer(String accessToken,
                                       String refreshToken,
                                       long expiresInSeconds,
                                       String publicId,
                                       String username,
                                       String fullName,
                                       String role) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds,
                publicId, username, fullName, role);
    }

    /** Overridden so an accidental log statement cannot print either token. */
    @Override
    public String toString() {
        return "TokenResponse{publicId=" + publicId + ", username=" + username
                + ", role=" + role + ", accessToken=[REDACTED], refreshToken=[REDACTED]}";
    }
}
