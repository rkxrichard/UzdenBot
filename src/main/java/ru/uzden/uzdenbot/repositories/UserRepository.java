package ru.uzden.uzdenbot.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.uzden.uzdenbot.entities.User;

@Repository
public interface UserRepository extends JpaRepository<User,Long> {
}
