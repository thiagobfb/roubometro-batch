package br.com.roubometro.domain.model;

import jakarta.persistence.*;

@Entity
@Table(name = "municipalities")
public class Municipality {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "state_id", nullable = false)
    private Long stateId;

    @Column
    private String region;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getStateId() { return stateId; }
    public void setStateId(Long stateId) { this.stateId = stateId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
