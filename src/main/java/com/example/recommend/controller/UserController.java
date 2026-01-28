package com.example.recommend.controller;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.recommend.config.JwtUtil;
import com.example.recommend.domain.User;
import com.example.recommend.dto.user.UserExtraInfoRequest;
import com.example.recommend.service.UserService;
import com.example.recommend.controller.dto.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    /* ================= 인증 ================= */

    @PostMapping("/users")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.register(request));
    }

    @GetMapping("/users/check-email")
    public String checkEmail(@RequestParam String email) {
        return userService.isEmailDuplicate(email) ? "DUPLICATE" : "OK";
    }

    @GetMapping("/users/check-nickname")
    public String checkNickname(@RequestParam String nickname) {
        return userService.checkNicknameAvailability(nickname);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {

        User user = userService.login(request.getEmail(), request.getPassword());
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        String token = jwtUtil.generateToken(user.getEmail());
        System.out.println("[LOGIN] email=" + request.getEmail());
        System.out.println("[LOGIN] pwNull=" + (request.getPassword() == null));

        return ResponseEntity.ok(new LoginResponse(token, user.getEmail(), user.getNickname()));
        
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    /* ================= 내 정보 ================= */

    @GetMapping("/user/me")
    public ResponseEntity<UserInfoResponse> getMyInfo() {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        User user = userService.findByEmail(email);
        if (user == null) return ResponseEntity.notFound().build();

        String role = user.getRole();
        if (role == null || role.isBlank()) role = "USER";

        // ✅ 온보딩 정보 재사용
        var onb = userService.getOnboardingInfo(email);

        return ResponseEntity.ok(
                new UserInfoResponse(
                        user.getEmail(),
                        user.getNickname(),
                        user.getCreatedAt(),
                        user.getProfileImageUrl(),
                        role,

                        user.getRealName(),
                        user.getBirthDate(),
                        user.getGender(),
                        user.getPhone(),
                        Boolean.TRUE.equals(user.getPhoneVerified()),
                        user.getVerifiedPhone(),

                        onb.onboardingDone(),
                        onb.preferredGenres()
                )
        );
    }

    @PutMapping("/user/nickname")
    public ResponseEntity<Void> updateNickname(@RequestBody UpdateNicknameRequest request) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        userService.updateNickname(email, request.getNickname());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/user/password")
    public ResponseEntity<String> changePassword(@RequestBody ChangePasswordRequest request) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        boolean success = userService.changePassword(email, request.getCurrentPassword(), request.getNewPassword());

        if (!success) {
            return ResponseEntity.badRequest().body("현재 비밀번호가 일치하지 않습니다.");
        }

        return ResponseEntity.ok("비밀번호가 변경되었습니다.");
    }

    @PutMapping("/user/extra-info")
    public ResponseEntity<Void> updateExtraInfo(@RequestBody UserExtraInfoRequest request) {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        userService.updateExtraInfo(email, request);
        return ResponseEntity.ok().build();
    }

    /* ================= ✅ 필수5: 온보딩(장르 선택) ================= */

    @GetMapping("/user/onboarding")
    public ResponseEntity<OnboardingResponse> onboarding() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        var info = userService.getOnboardingInfo(email);
        return ResponseEntity.ok(new OnboardingResponse(info.onboardingDone(), info.preferredGenres()));
    }

    @PutMapping("/user/onboarding/genres")
    public ResponseEntity<Void> saveGenres(@RequestBody OnboardingGenresRequest request) {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        List<String> genres = request == null ? null : request.getGenres();
        userService.savePreferredGenres(email, genres);
        return ResponseEntity.ok().build();
    }

    public static class OnboardingGenresRequest {
        private List<String> genres;
        public List<String> getGenres() { return genres; }
        public void setGenres(List<String> genres) { this.genres = genres; }
    }

    public record OnboardingResponse(boolean onboardingDone, List<String> preferredGenres) {}

    /* ================= 휴대폰 인증 ================= */

    @PostMapping("/user/phone/request")
    public ResponseEntity<Void> requestPhoneVerification(@RequestBody PhoneVerificationRequest request) {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        userService.requestPhoneVerification(email, request.getPhone());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/user/phone/verify")
    public ResponseEntity<String> verifyPhone(@RequestBody PhoneVerificationConfirmRequest request) {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        boolean success = userService.verifyPhoneCode(email, request.getCode());
        if (!success) {
            throw new IllegalArgumentException("인증번호가 올바르지 않습니다.");
        }

        return ResponseEntity.ok("휴대폰 인증 완료");
    }

    @DeleteMapping("/user")
    public ResponseEntity<Void> deleteUser() {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        userService.deleteByEmail(email);
        return ResponseEntity.ok().build();
    }

    /* ================= 프로필 이미지 ================= */

    @PostMapping("/user/profile-image")
    public ResponseEntity<String> uploadProfileImage(@RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어 있습니다.");
        }

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        String uploadDir = "C:/upload/profile";
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();

        String ext = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
        String fileName = UUID.randomUUID() + ext;

        File saveFile = new File(dir, fileName);
        file.transferTo(saveFile);

        String imageUrl = "/uploads/profile/" + fileName;
        userService.updateProfileImage(email, imageUrl);

        return ResponseEntity.ok(imageUrl);
    }

    @DeleteMapping("/user/profile-image")
    public ResponseEntity<Void> deleteProfileImage() {

        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        User user = userService.findByEmail(email);
        if (user == null) return ResponseEntity.notFound().build();

        String imageUrl = user.getProfileImageUrl();

        if (imageUrl != null && !imageUrl.contains("default.png")) {
            String filePath = "C:/upload/profile/" + imageUrl.substring(imageUrl.lastIndexOf("/") + 1);

            File file = new File(filePath);
            if (file.exists()) file.delete();
        }

        userService.updateProfileImage(email, null);
        return ResponseEntity.ok().build();
    }
}
