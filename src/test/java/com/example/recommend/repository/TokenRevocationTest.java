package com.example.recommend.repository;

import com.example.recommend.domain.User;
import com.example.recommend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
class TokenRevocationTest {
    @Autowired UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test void passwordChangeAndLogoutPersistRevocationAndStaleProfileSaveCannotUndoIt() {
        var service = new UserService(users, encoder, mock(NicknameBlacklistRepository.class),
                mock(TermsAgreementRepository.class), mock(PhoneVerificationService.class), mock(RelatedDataDeletion.class));
        User original = users.saveAndFlush(User.builder().email("token@example.com")
                .nickname("tokenuser").password(encoder.encode("oldpass123")).build());
        Long id = original.getId();
        assertFalse(service.changePassword(original.getEmail(), "wrong", "newpass123"));
        assertEquals(0, users.findById(id).orElseThrow().getTokenVersion());
        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword(original.getEmail(), "oldpass123", "short"));
        assertEquals(0, users.findById(id).orElseThrow().getTokenVersion());
        assertTrue(service.changePassword(original.getEmail(), "oldpass123", "newpass123"));
        User changed = users.findById(id).orElseThrow();
        assertEquals(1, changed.getTokenVersion());
        assertTrue(encoder.matches("newpass123", changed.getPassword()));
        service.logout(changed.getEmail());
        assertEquals(2, users.findById(id).orElseThrow().getTokenVersion());
        // A profile writer loaded before logout must not restore version 1.
        changed.setNickname("updated");
        users.saveAndFlush(changed);
        users.revokeTokens(id); // clears the persistence context and increments once more
        assertEquals(3, users.findById(id).orElseThrow().getTokenVersion());
    }
}
