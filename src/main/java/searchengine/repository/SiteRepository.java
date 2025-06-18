package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import searchengine.model.SiteModel;
import searchengine.model.enums.IndexStatus;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Transactional
public interface SiteRepository extends JpaRepository<SiteModel, Integer> {

    Optional<SiteModel> findByUrl(String url);

    List<SiteModel> findByStatus(IndexStatus status);

    List<SiteModel> findByLastErrorIsNotNull();

    Integer countByStatus(IndexStatus status);

    void deleteByUrl(String url);

}
