package com.example.recommend.service;

import com.example.recommend.domain.PhoneVerification;
import com.example.recommend.repository.PhoneVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    private final PhoneVerificationRepository repo;

    // 숫자만 남겨서 동일번호 판단 안정화
    public String normalizePhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "").trim();
    }

    private String gen6() {
        int n = new Random().nextInt(900000) + 100000;
        return String.valueOf(n);
    }

    @Transactional
    public PhoneVerification request(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        if (phone.isBlank()) throw new IllegalArgumentException("휴대폰 번호를 입력해주세요.");

        String code = gen6();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(3);

        PhoneVerification pv = PhoneVerification.builder()
                .phone(phone)
                .code(code)
                .expireAt(expireAt)
                .verified(false)
                .build();

        return repo.save(pv);
    }

    /**
     * 공개: 인증 확인(회원가입 전)
     */
    @Transactional
    public void verify(String rawPhone, String rawCode) {
        String phone = normalizePhone(rawPhone);
        String code = rawCode == null ? "" : rawCode.trim();

        if (phone.isBlank()) throw new IllegalArgumentException("휴대폰 번호를 입력해주세요.");
        if (code.isBlank()) throw new IllegalArgumentException("인증번호를 입력해주세요.");

        PhoneVerification pv = repo.findTopByPhoneOrderByIdDesc(phone)
                .orElseThrow(() -> new IllegalArgumentException("인증 요청이 없습니다. 먼저 인증번호를 요청하세요."));

        if (Boolean.TRUE.equals(pv.getVerified())) {
            // 이미 인증된 상태면 굳이 실패로 치지 않아도 됨(UX)
            return;
        }

        if (pv.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("인증번호가 만료되었습니다. 다시 요청하세요.");
        }

        if (!pv.getCode().equals(code)) {
            throw new IllegalArgumentException("인증번호가 올바르지 않습니다.");
        }

        pv.setVerified(true);
        pv.setVerifiedAt(LocalDateTime.now());
        repo.save(pv);
    }

    /**
     * 회원가입 시 검증용: "이 번호가 최근 인증 완료인지"
     */
    @Transactional(readOnly = true)
    public boolean isVerifiedNow(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        if (phone.isBlank()) return false;

        return repo.findTopByPhoneOrderByIdDesc(phone)
                .filter(pv -> Boolean.TRUE.equals(pv.getVerified()))
                .filter(pv -> pv.getExpireAt().isAfter(LocalDateTime.now())) // 인증도 유효시간 내만 인정(정책)
                .isPresent();
    }
}

