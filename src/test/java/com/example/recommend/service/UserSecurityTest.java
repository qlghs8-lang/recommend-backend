package com.example.recommend.service;

import com.example.recommend.domain.User;
import com.example.recommend.exception.ConflictException;
import com.example.recommend.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSecurityTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final NicknameBlacklistRepository blacklist = mock(NicknameBlacklistRepository.class);
    private final UserService service = new UserService(users, encoder, blacklist,
            mock(TermsAgreementRepository.class), mock(PhoneVerificationService.class), mock(RelatedDataDeletion.class));

    @Test void registrationAndPasswordChangeRejectSameInvalidPasswords() {
        User existing = User.builder().id(1L).password("hash").build();
        when(users.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(existing));
        when(encoder.matches("current", "hash")).thenReturn(true);
        for (String password : new String[]{null, "", "short1", "onlyletters", "12345678", "abc1234!", "a1".repeat(37)}) {
            assertThrows(IllegalArgumentException.class, () -> service.register(User.builder()
                    .email("user@example.com").password(password).nickname("tester").build()));
            assertThrows(IllegalArgumentException.class, () -> service.changePassword("user@example.com", "current", password));
        }
        verify(users, never()).save(any());
        verify(encoder, never()).encode(any());
    }

    @Test void validPasswordChangeStoresOnlyEncodedPassword() {
        User existing = User.builder().id(1L).password("old-hash").build();
        when(users.findByEmailForUpdate("user@example.com")).thenReturn(Optional.of(existing));
        when(encoder.matches("current", "old-hash")).thenReturn(true);
        when(encoder.encode("newpass123")).thenReturn("new-hash");
        assertTrue(service.changePassword("user@example.com", "current", "newpass123"));
        assertEquals("new-hash", existing.getPassword());
        verify(users).save(existing);
        verify(users).revokeTokens(1L);
    }

    @Test void duplicateNicknameIsAConflict() {
        when(blacklist.findAll()).thenReturn(List.of());
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(User.builder().nickname("before").build()));
        when(users.existsByNickname("taken")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.updateNickname("user@example.com", "taken"));
        verify(users, never()).save(any());
    }
}
