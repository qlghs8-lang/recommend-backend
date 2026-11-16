package com.example.recommend.service;

import com.example.recommend.controller.dto.RegisterRequest;
import com.example.recommend.exception.ConflictException;
import com.example.recommend.exception.ForbiddenException;
import com.example.recommend.exception.ResourceNotFoundException;
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
    private final RelatedDataDeletion relatedDataDeletion;

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

        // Validate terms before consuming the one-use phone ticket.
        Map<String, Boolean> t = request.getTermsAgreements();
        if (t == null) t = Map.of();

        boolean service = Boolean.TRUE.equals(t.get("service"));
        boolean privacy = Boolean.TRUE.equals(t.get("privacy"));
        boolean age14 = Boolean.TRUE.equals(t.get("age14"));
        boolean marketing = Boolean.TRUE.equals(t.get("marketing"));

        if (!service || !privacy || !age14) {
            throw new IllegalArgumentException("필수 약관에 동의해야 가입할 수 있습니다.");
        }

        User saved = register(user, request.getPhoneVerificationTicket(), request.getPhoneRequesterToken());

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

    public User register(User user) {
        return register(user, null, null);
    }

    private User register(User user, String ticket, String requesterToken) {

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("이메일은 필수입니다.");
        }

        if (!user.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) {
            throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다.");
        }

        PasswordPolicy.validate(user.getPassword());

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
            throw new ConflictException("이미 사용 중인 이메일입니다.");
        }

        if (userRepository.existsByNickname(nickname)) {
            throw new ConflictException("이미 사용 중인 닉네임입니다.");
        }

        user.setEmail(email);
        user.setNickname(nickname);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setPhoneVerified(false);
        user.setProfileImageUrl("/uploads/profile/default.png");

        // 기본값(온보딩)
        if (user.getOnboardingDone() == null) user.setOnboardingDone(false);

        user.setVerifiedPhone(null);
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            String normalized = phoneVerificationService.normalizePhone(user.getPhone());
            if (userRepository.existsByVerifiedPhone(normalized))
                throw new ConflictException("이미 인증된 휴대폰 번호입니다.");
            phoneVerificationService.consume(ticket, normalized,
                    phoneVerificationService.registrationOwner(requesterToken));
            user.setPhone(normalized);
            user.setPhoneVerified(true);
            user.setVerifiedPhone(normalized);
        } else {
            user.setPhone(null);
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
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));

        if (!trimmed.equals(user.getNickname())
                && userRepository.existsByNickname(trimmed)) {
            throw new ConflictException("이미 사용 중인 닉네임입니다.");
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

    @Transactional
    public boolean changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmailForUpdate(email).orElse(null);
        if (user == null) return false;

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return false;
        }

        PasswordPolicy.validate(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        userRepository.revokeTokens(user.getId());
        return true;
    }

    @Transactional
    public void logout(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));
        userRepository.revokeTokens(user.getId());
    }

    @Transactional
    public void updateExtraInfo(String email, UserExtraInfoRequest request) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));

        user.setRealName(request.getRealName());
        user.setBirthDate(request.getBirthDate());
        user.setGender(request.getGender());
        String phone = phoneVerificationService.normalizePhone(request.getPhone());
        String oldPhone = user.getPhone() == null ? "" : user.getPhone().replaceAll("[\\s-]", "");
        user.setPhone(phone.isEmpty() ? null : phone);
        if (!phone.equals(oldPhone)) {
            user.setPhoneVerified(false);
            user.setVerifiedPhone(null);
            user.setPhoneVerifyCode(null);
            user.setPhoneVerifyExpireAt(null);
        }

        userRepository.save(user);
    }

    /* ================= 휴대폰 인증 ================= */

    public PhoneVerificationService.RequestResult requestPhoneVerification(String email, String phone, String remoteAddress) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));
        return phoneVerificationService.request(phone, "user:" + user.getId(), remoteAddress);
    }

    @Transactional
    public void verifyPhoneCode(String email, String phone, String challengeId, String code) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));
        String normalized = phoneVerificationService.normalizePhone(phone);
        String owner = "user:" + user.getId();
        var result = phoneVerificationService.verify(challengeId, normalized, code, owner);
        phoneVerificationService.consume(result.verificationTicket(), normalized, owner);
        if (!normalized.equals(user.getVerifiedPhone()) && userRepository.existsByVerifiedPhone(normalized))
            throw new ConflictException("이미 인증된 휴대폰 번호입니다.");
        user.setPhone(normalized);
        user.setPhoneVerified(true);
        user.setVerifiedPhone(normalized);
        user.setPhoneVerifyCode(null);
        user.setPhoneVerifyExpireAt(null);
        userRepository.save(user);
    }

    private void validatePhoneVerified(User user) {
        if (!Boolean.TRUE.equals(user.getPhoneVerified())) {
            throw new ForbiddenException("휴대폰 인증이 필요합니다.");
        }
    }

    /* ================= 프로필 이미지 ================= */

    public void updateProfileImage(String email, String imageUrl) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));

        user.setProfileImageUrl(
                imageUrl != null
                        ? imageUrl
                        : "/uploads/profile/default.png"
        );

        userRepository.save(user);
    }

    /* ================= 탈퇴 ================= */

    @Transactional
    public void deleteByEmail(String email) {
        userRepository.findByEmailForUpdate(email).ifPresent(user -> {
            relatedDataDeletion.forUser(user.getId());
            userRepository.delete(user);
            userRepository.flush();
        });
    }

    @Transactional(readOnly = true)
    public OnboardingInfo getOnboardingInfo(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));

        List<String> genres = parseGenresCsv(user.getPreferredGenres());
        boolean done = Boolean.TRUE.equals(user.getOnboardingDone());

        return new OnboardingInfo(done, genres);
    }

    @Transactional
    public void savePreferredGenres(String email, List<String> genres) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("사용자 없음"));

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
