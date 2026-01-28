package com.example.recommend.service;

import com.example.recommend.controller.dto.RegisterRequest;
import com.example.recommend.domain.NicknameBlacklist;
import com.example.recommend.domain.TermsAgreement;
import com.example.recommend.domain.User;
import com.example.recommend.dto.user.UserExtraInfoRequest;
import com.example.recommend.repository.NicknameBlacklistRepository;
import com.example.recommend.repository.TermsAgreementRepository;
import com.example.recommend.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NicknameBlacklistRepository nicknameBlacklistRepository;
    private final TermsAgreementRepository termsAgreementRepository;
    private final PhoneVerificationService phoneVerificationService;

    /* ================= 회원가입 (프론트 요청 DTO) ================= */

    @Transactional
    public User register(RegisterRequest request) {

        if (request == null) {
            throw new IllegalArgumentException("요청이 비어 있습니다.");
        }

        // 1) User로 변환
        User user = User.builder()
                .email(request.getEmail())
                .password(request.getPassword())
                .nickname(request.getNickname())
                .realName(request.getRealName())
                .birthDate(request.getBirthDate())
                .gender(request.getGender())
                .phone(request.getPhone())
                .build();

        // 2) 기존 회원가입 로직 재사용 (검증/중복/암호화/기본값/저장)
        User saved = register(user);

        // 3) 약관 동의 저장 + 필수 동의 서버 강제
        Map<String, Boolean> t = request.getTermsAgreements();
        if (t == null) t = Map.of();

        boolean service = Boolean.TRUE.equals(t.get("service"));
        boolean privacy = Boolean.TRUE.equals(t.get("privacy"));
        boolean age14 = Boolean.TRUE.equals(t.get("age14"));
        boolean marketing = Boolean.TRUE.equals(t.get("marketing"));

        if (!service || !privacy || !age14) {
            throw new IllegalArgumentException("필수 약관에 동의해야 가입할 수 있습니다.");
        }

        saveTerms(saved, "service", service);
        saveTerms(saved, "privacy", privacy);
        saveTerms(saved, "age14", age14);
        saveTerms(saved, "marketing", marketing);

        return saved;
    }

    private void saveTerms(User user, String key, boolean agreed) {
        termsAgreementRepository.save(
                TermsAgreement.builder()
                        .user(user)
                        .termKey(key)
                        .agreed(agreed)
                        .agreedAt(LocalDateTime.now())
                        .build()
        );
    }

    /* ================= 회원가입 (기존 로직 유지) ================= */

    public User register(User user) {

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("이메일은 필수입니다.");
        }

        if (!user.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다.");
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new IllegalArgumentException("비밀번호는 필수입니다.");
        }

        if (!user.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,}$")) {
            throw new IllegalArgumentException(
                    "비밀번호는 영문과 숫자를 포함한 8자리 이상이어야 합니다."
            );
        }

        if (user.getNickname() == null || user.getNickname().isBlank()) {
            throw new IllegalArgumentException("닉네임은 필수입니다.");
        }

        String email = user.getEmail().trim();
        String nickname = user.getNickname().trim();

        if (!nickname.matches("^[가-힣a-zA-Z0-9]{2,10}$")) {
            throw new IllegalArgumentException(
                    "닉네임은 2~10자의 한글, 영문, 숫자만 가능합니다."
            );
        }

        validateNicknameBlacklist(nickname);

        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException("이미 사용 중인 이메일입니다.");
        }

        if (userRepository.existsByNickname(nickname)) {
            throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
        }

        user.setEmail(email);
        user.setNickname(nickname);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setPhoneVerified(false);
        user.setProfileImageUrl("/uploads/profile/default.png");

        // ✅ 필수5 기본값(온보딩)
        if (user.getOnboardingDone() == null) user.setOnboardingDone(false);
        
     // ✅ 회원가입 시 phone이 들어오면: 공개 인증 완료 여부 확인 후 반영
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            String normalized = phoneVerificationService.normalizePhone(user.getPhone());
            user.setPhone(normalized);

            boolean ok = phoneVerificationService.isVerifiedNow(normalized);
            if (!ok) {
                // 정책 선택:
                // 1) phone을 넣었으면 인증 필수로 강제
                throw new IllegalArgumentException("휴대폰 인증이 필요합니다. 인증 완료 후 가입해주세요.");

                // 2) 아니면 phone만 저장하고 미인증으로 남겨도 됨(원하면 이 방식으로 바꿔줄게)
                // user.setPhoneVerified(false);
                // user.setVerifiedPhone(null);
            }

            user.setPhoneVerified(true);
            user.setVerifiedPhone(normalized);
        }

        return userRepository.save(user);
    }

    /* ================= 닉네임 사용 가능 여부 체크 ================= */

    public String checkNicknameAvailability(String nickname) {

        if (nickname == null || nickname.isBlank()) return "EMPTY";

        String trimmed = nickname.trim();

        if (!trimmed.matches("^[가-힣a-zA-Z0-9]{2,10}$")) {
            return "INVALID_FORMAT";
        }

        try {
            validateNicknameBlacklist(trimmed);
        } catch (IllegalArgumentException e) {
            return "BLACKLIST";
        }

        if (userRepository.existsByNickname(trimmed)) {
            return "DUPLICATE";
        }

        return "OK";
    }

    /* ================= 로그인 ================= */

    public User login(String email, String password) {
        if (email == null || password == null) return null;

        User user = userRepository.findByEmail(email.trim()).orElse(null);
        if (user == null) return null;

        return passwordEncoder.matches(password, user.getPassword()) ? user : null;
    }

    /* ================= 조회 ================= */

    public boolean isEmailDuplicate(String email) {
        if (email == null) return true;
        return userRepository.findByEmail(email.trim()).isPresent();
    }

    public User findByEmail(String email) {
        if (email == null) return null;
        return userRepository.findByEmail(email.trim()).orElse(null);
    }

    /* ================= 수정 ================= */

    public void updateNickname(String email, String nickname) {

        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("닉네임은 비어있을 수 없습니다.");
        }

        String trimmed = nickname.trim();

        if (!trimmed.matches("^[가-힣a-zA-Z0-9]{2,10}$")) {
            throw new IllegalArgumentException(
                    "닉네임은 2~10자의 한글, 영문, 숫자만 가능합니다."
            );
        }

        validateNicknameBlacklist(trimmed);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        if (!trimmed.equals(user.getNickname())
                && userRepository.existsByNickname(trimmed)) {
            throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
        }

        user.setNickname(trimmed);
        userRepository.save(user);
    }

    private void validateNicknameBlacklist(String nickname) {

        String lower = nickname.toLowerCase();

        for (NicknameBlacklist b : nicknameBlacklistRepository.findAll()) {
            if (lower.contains(b.getWord())) {
                throw new IllegalArgumentException("사용할 수 없는 닉네임입니다.");
            }
        }
    }

    public boolean changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return false;

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return false;
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return true;
    }

    @Transactional
    public void updateExtraInfo(String email, UserExtraInfoRequest request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        user.setRealName(request.getRealName());
        user.setBirthDate(request.getBirthDate());
        user.setGender(request.getGender());
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            user.setPhone(request.getPhone());
            user.setPhoneVerified(false);
            user.setVerifiedPhone(null);
        }

        userRepository.save(user);
    }

    /* ================= 휴대폰 인증 ================= */

    @Transactional
    public void requestPhoneVerification(String email, String phone) {

        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("휴대폰 번호를 입력해주세요.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        String p = phone.trim();

        // ✅ 이미 인증된 번호면 재요청 금지 (핵심)
        if (Boolean.TRUE.equals(user.getPhoneVerified())
                && user.getVerifiedPhone() != null
                && user.getVerifiedPhone().equals(p)) {
            throw new IllegalStateException("이미 인증된 휴대폰 번호입니다.");
        }

        // ✅ 다른 번호로 인증을 시도하는 경우에만 미인증으로 리셋
        user.setPhone(p);
        user.setPhoneVerified(false);
        user.setVerifiedPhone(null);

        String code = String.valueOf((int) (Math.random() * 900000) + 100000);

        user.setPhoneVerifyCode(code);
        user.setPhoneVerifyExpireAt(LocalDateTime.now().plusMinutes(3));
    }

    @Transactional
    public boolean verifyPhoneCode(String email, String code) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        if (user.getPhoneVerifyCode() == null) return false;
        if (user.getPhoneVerifyExpireAt().isBefore(LocalDateTime.now())) return false;
        if (!user.getPhoneVerifyCode().equals(code)) return false;

        if (userRepository.existsByVerifiedPhone(user.getPhone())) {
            throw new IllegalStateException("이미 인증된 휴대폰 번호입니다.");
        }

        user.setPhoneVerified(true);
        user.setVerifiedPhone(user.getPhone());

        user.setPhoneVerifyCode(null);
        user.setPhoneVerifyExpireAt(null);

        return true;
    }

    private void validatePhoneVerified(User user) {
        if (!Boolean.TRUE.equals(user.getPhoneVerified())) {
            throw new IllegalStateException("휴대폰 인증이 필요합니다.");
        }
    }

    /* ================= 프로필 이미지 ================= */

    public void updateProfileImage(String email, String imageUrl) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return;

        user.setProfileImageUrl(
                imageUrl != null
                        ? imageUrl
                        : "/uploads/profile/default.png"
        );

        userRepository.save(user);
    }

    /* ================= 탈퇴 ================= */

    public void deleteByEmail(String email) {
        userRepository.findByEmail(email)
                .ifPresent(userRepository::delete);
    }

    /* =========================================================
     * ✅ 필수5: 온보딩(장르 선택) 저장/조회
     * ========================================================= */

    @Transactional(readOnly = true)
    public OnboardingInfo getOnboardingInfo(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        List<String> genres = parseGenresCsv(user.getPreferredGenres());
        boolean done = Boolean.TRUE.equals(user.getOnboardingDone());

        return new OnboardingInfo(done, genres);
    }

    @Transactional
    public void savePreferredGenres(String email, List<String> genres) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        List<String> cleaned = normalizeGenres(genres);

        if (cleaned.size() < 3) {
            throw new IllegalArgumentException("장르는 최소 3개 이상 선택해야 합니다.");
        }

        user.setPreferredGenres(String.join(",", cleaned));
        user.setOnboardingDone(true);
        userRepository.save(user);
    }

    private List<String> normalizeGenres(List<String> genres) {
        if (genres == null) return List.of();

        // 공백 제거 + 소문자 + 빈값 제거 + 중복 제거(순서 유지)
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String g : genres) {
            if (g == null) continue;
            String s = g.trim().toLowerCase();
            if (s.isBlank()) continue;
            set.add(s);
        }

        // 너무 긴 값 방어(악성 요청 방지)
        return set.stream()
                .map(s -> s.length() > 30 ? s.substring(0, 30) : s)
                .collect(Collectors.toList());
    }

    private List<String> parseGenresCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }
    

    public record OnboardingInfo(boolean onboardingDone, List<String> preferredGenres) {}
}
