package searchengine.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Indexed;

import javax.persistence.*;

@Entity
@Getter
@Setter
@Table(name = "page",
        indexes = {
        @Index(name = "idx_path", columnList = "path", unique = true)
        })
public class PageModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "site_id", nullable = false)
    private SiteModel siteModel;

    @Column(name = "path", length = 255, columnDefinition = "VARCHAR(255)", nullable = false)
    private String path;

    @Column(nullable = false)
    private Integer code;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;
}
