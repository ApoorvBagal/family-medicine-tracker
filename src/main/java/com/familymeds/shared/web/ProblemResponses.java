package com.familymeds.shared.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes problem+json from servlet filters (Spring Security), where the MVC exception handler doesn't
 * apply. Bodies are fixed strings, so no JSON library or escaping of user input is involved.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
