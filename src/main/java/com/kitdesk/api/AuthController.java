package com.kitdesk.api;

import com.kitdesk.service.AuthService;
import com.kitdesk.service.Views.LoginResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /auth/login}: the only operation (besides health and docs) that needs no token. */
@RestController
public class AuthController {

    /** The contract's LoginRequest. Unknown fields are refused (400) by the JSON settings. */
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/login")
    public LoginResult login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }
}
