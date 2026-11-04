package com.fgc.backend.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final TokenService tokens;

    public AuthController(AuthService auth, TokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    public record SignupRequest(@NotBlank @Email @Size(max = 200) String email,
                                @NotBlank @Size(min = 8, max = 100) String password,
                                @NotBlank @Size(max = 50) String displayName) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenService.Tokens signup(@Valid @RequestBody SignupRequest r) {
        return auth.signup(r.email(), r.password(), r.displayName());
    }

    @PostMapping("/login")
    public TokenService.Tokens login(@Valid @RequestBody LoginRequest r) {
        return auth.login(r.email(), r.password());
    }

    @PostMapping("/refresh")
    public TokenService.Tokens refresh(@Valid @RequestBody RefreshRequest r) {
        return tokens.rotate(r.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest r) {
        tokens.revoke(r.refreshToken());
    }
}
