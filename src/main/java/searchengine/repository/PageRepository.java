package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.PageModel;
import searchengine.model.SiteModel;

import javax.persistence.LockModeType;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Transactional
public interface PageRepository extends JpaRepository<PageModel, Integer> {

    List<PageModel> findBySiteModelId(Integer siteId);

    void deleteBySiteModelUrl(String siteUrl);

    PageModel findByPathAndSiteModelId(String path, Integer siteModelId);

    boolean existsByPathAndSiteModel(String url, SiteModel siteModel);



}
