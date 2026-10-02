package com.example.seatreservation.security;

import com.example.seatreservation.exception.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
public class AuthService {
    public AuthUser requireUser(HttpServletRequest request) {
        AuthUser user = (AuthUser) request.getAttribute(AuthFilter.USER_ATTRIBUTE);
        if (user == null) throw new DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Valid bearer token required");
        return user;
    }

    public AuthUser requireAdmin(HttpServletRequest request) {
        AuthUser user = requireUser(request);
        if (!user.admin()) throw new DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin token required");
        return user;
    }
}
