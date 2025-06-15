package searchengine.model;

import lombok.Getter;
import lombok.Setter;
import searchengine.model.enums.IndexStatus;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "site")
public class SiteModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED')")
    private IndexStatus status;

    @Column(name = "status_time")
    private LocalDateTime statusTime;

    @Column(columnDefinition = "VARCHAR(255)", nullable = false)
    private String url;

    @Column(columnDefinition = "VARCHAR(255)",nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @OneToMany(mappedBy = "siteModel", cascade = CascadeType.ALL)
    private List<PageModel> pageModels;
}
