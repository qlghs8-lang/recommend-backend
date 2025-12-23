package com.example.recommend.service;

import com.example.recommend.domain.NicknameBlacklist;
import com.example.recommend.domain.User;
import com.example.recommend.dto.user.UserExtraInfoRequest;
import com.example.recommend.repository.NicknameBlacklistRepository;
import com.example.recommend.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NicknameBlacklistRepository nicknameBlacklistRepository;

    /* ================= 회원가입 ================= */

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

        // 1️⃣ 형식 / 길이 정책
        if (!trimmed.matches("^[가-힣a-zA-Z0-9]{2,10}$")) {
            throw new IllegalArgumentException(
                "닉네임은 2~10자의 한글, 영문, 숫자만 가능합니다."
            );
        }
        
        validateNicknameBlacklist(trimmed);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));

        // 2️⃣ 자기 자신 제외 중복 체크
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
                throw new IllegalArgumentException(
                    "사용할 수 없는 닉네임입니다."
                );
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
            user.setVerifiedPhone(null); // 🔥 반드시 초기화
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

        user.setPhone(phone.trim());
        user.setPhoneVerified(false);

        String code = String.valueOf((int)(Math.random() * 900000) + 100000);

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

        // 🔥 중복 인증 방지
        if (userRepository.existsByVerifiedPhone(user.getPhone())) {
            throw new IllegalStateException("이미 인증된 휴대폰 번호입니다.");
        }

        user.setPhoneVerified(true);
        user.setVerifiedPhone(user.getPhone());

        user.setPhoneVerifyCode(null);
        user.setPhoneVerifyExpireAt(null);

        return true;
    }
    
    // ✅ 앞으로 "행동성 기능"에 붙일 인증 강제 체크
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
}
