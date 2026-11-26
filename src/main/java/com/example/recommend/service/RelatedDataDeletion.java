package com.example.recommend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bulk cleanup must participate in the transaction that removes the parent. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RelatedDataDeletion {
    @PersistenceContext private EntityManager em;

    public void forUser(Long id) {
        delete("delete from ChatMessage x where x.room.user.id = :id", id);
        delete("delete from ChatRoom x where x.user.id = :id", id);
        logs("userId", id);
        delete("delete from UserContentInteraction x where x.user.id = :id", id);
        delete("delete from TermsAgreement x where x.user.id = :id", id);
        em.clear(); // Bulk DML does not evict already managed child entities.
    }

    public void forContent(Long id) {
        logs("contentId", id);
        delete("delete from UserContentInteraction x where x.content.id = :id", id);
        em.clear();
    }

    private void logs(String field, Long id) {
        delete("delete from RecommendClickLog x where x." + field + " = :id or x.recommendLogId in "
                + "(select r.id from RecommendLog r where r." + field + " = :id)", id);
        delete("delete from RecommendLog x where x." + field + " = :id", id);
    }

    private void delete(String jpql, Long id) {
        em.createQuery(jpql).setParameter("id", id).executeUpdate();
    }
}
