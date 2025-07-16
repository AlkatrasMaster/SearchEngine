package searchengine.model;


import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Getter
@Setter
@Table(name = "indexes")
public class IndexModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "page_id", nullable = false)
    private PageModel page;

    @ManyToOne
    @JoinColumn(name = "lemma_id", nullable = false)
    private LemmaModel lemma;

    @Column(name = "rank_val", nullable = false)
    private float rank;
}
