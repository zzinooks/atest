package com.fgc.backend.family;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.fgc.backend.common.Auth.userId;

@RestController
@RequestMapping("/api/v1")
public class FamilyController {
    private final FamilyService family;

    public FamilyController(FamilyService family) {
        this.family = family;
    }

    public record InviteRequest(@NotBlank @Size(max = 30) String relation, @NotNull @Valid FamilyService.Permissions permissions) {}

    public record AcceptRequest(@NotBlank @Size(max = 20) String code) {}

    @PostMapping("/patients/{patientId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyService.Invitation invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                           @Valid @RequestBody InviteRequest r) {
        return family.invite(userId(jwt), patientId, r.relation(), r.permissions());
    }

    @PostMapping("/invitations/accept")
    public Map<String, UUID> accept(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AcceptRequest r) {
        return Map.of("patientId", family.accept(userId(jwt), r.code()));
    }

    @GetMapping("/patients/{patientId}/members")
    public List<FamilyService.Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return family.members(userId(jwt), patientId);
    }

    @PutMapping("/patients/{patientId}/members/{memberId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID memberId,
                       @Valid @RequestBody FamilyService.Permissions p) {
        family.updatePermissions(userId(jwt), patientId, memberId, p);
    }

    @DeleteMapping("/patients/{patientId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @PathVariable UUID memberId) {
        family.revoke(userId(jwt), patientId, memberId);
    }

    @DeleteMapping("/me/family/{patientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        family.leave(userId(jwt), patientId);
    }
}
