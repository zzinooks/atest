package com.fgc.backend.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record PatientProfileRequest(@Size(max = 50) String timeZone) {}

    public record DeviceRequest(@NotBlank @Size(max = 4096) String pushToken,
                                @NotBlank @Pattern(regexp = "ANDROID|IOS|WEB") String platform) {}

    public record WithdrawRequest(@NotBlank String password) {}

    @GetMapping
    public AccountService.Me me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.me(userId(jwt));
    }

    @PostMapping("/patient-profile")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> becomePatient(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PatientProfileRequest r) {
        return Map.of("patientId", accounts.createPatientProfile(userId(jwt), r.timeZone()));
    }

    @PostMapping("/devices")
    public Map<String, UUID> registerDevice(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest r) {
        return Map.of("deviceId", accounts.registerDevice(userId(jwt), r.platform(), r.pushToken()));
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
        accounts.removeDevice(userId(jwt), deviceId);
    }

    /** 회원 탈퇴 (비밀번호 재확인). 환자 데이터는 삭제, 계정은 식별정보 제거. */
    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WithdrawRequest r) {
        accounts.withdraw(userId(jwt), r.password());
    }
}
