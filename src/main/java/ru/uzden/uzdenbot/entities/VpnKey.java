package ru.uzden.uzdenbot.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "vpn_keys")
public class VpnKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
