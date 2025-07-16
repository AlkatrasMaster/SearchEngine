package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.LemmaModel;
import searchengine.model.SiteModel;

import java.util.Optional;

public interface LemmaRepository extends JpaRepository<LemmaModel, Integer> {
    Optional<LemmaModel> findByLemmaAndSite(String lemma, SiteModel site);
}
