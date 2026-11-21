package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.Category;
import com.copymylife.copymylife.model.CategoryVote;
import com.copymylife.copymylife.model.CategoryVoting;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.CategoryRepository;
import com.copymylife.copymylife.repository.CategoryVoteRepository;
import com.copymylife.copymylife.repository.CategoryVotingRepository;
import com.copymylife.copymylife.repository.LikedRoutineRepository;
import com.copymylife.copymylife.repository.RoutineRatingRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * US-28: the community files a routine under the category that fits it best. Someone who has
 * liked and rated a public routine votes once for one of the categories. The first vote opens the
 * routine's only voting period, VOTING_DAYS long; once it has ended the votes are counted, without
 * anyone asking, by finalizeDueVotings and on the next visit, and the routine is filed under the
 * category with the most votes. A tie keeps the routine's category when it is one of the leaders,
 * otherwise the leader first in the administrators' order wins. Counting again changes nothing.
 * Every vote and every count holds the routine's row, so votes and counts of one routine never
 * interleave; the database also keeps one vote per user and routine.
 */
@Service
public class CategoryVoteService {

    static final int VOTING_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(CategoryVoteService.class);

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final RoutineAccessService routineAccessService;
    private final CategoryRepository categoryRepository;
    private final CategoryVoteRepository voteRepository;
    private final CategoryVotingRepository votingRepository;
    private final LikedRoutineRepository likedRoutineRepository;
    private final RoutineRatingRepository routineRatingRepository;
    private final TransactionTemplate transaction;
    private final TransactionTemplate countTransaction;

    public CategoryVoteService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            RoutineAccessService routineAccessService,
            CategoryRepository categoryRepository,
            CategoryVoteRepository voteRepository,
            CategoryVotingRepository votingRepository,
            LikedRoutineRepository likedRoutineRepository,
            RoutineRatingRepository routineRatingRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.routineAccessService = routineAccessService;
        this.categoryRepository = categoryRepository;
        this.voteRepository = voteRepository;
        this.votingRepository = votingRepository;
        this.likedRoutineRepository = likedRoutineRepository;
        this.routineRatingRepository = routineRatingRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        // A count is kept even when the request that started it is then refused.
        this.countTransaction = new TransactionTemplate(transactionManager);
        this.countTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Choice(Long categoryId, String name) {
    }

    public record Result(Long categoryId, String name, long votes) {
    }

    // status: NOT_STARTED, OPEN or CLOSED. reason says why canVote is false: PRIVATE, CLOSED,
    // ALREADY_VOTED or LIKE_AND_RATE.
    public record Summary(
            String status,
            int votingDays,
            Instant closesAt,
            Instant closedAt,
            long totalVotes,
            List<Result> results,
            Choice winner,
            Choice myVote,
            boolean liked,
            boolean rated,
            boolean canVote,
            String reason
    ) {
    }

    public Summary getSummary(String email, Long routineId) {
        countIfDue(routineId);

        return transaction.execute(status -> {
            User user = requireUser(email);
            Routine routine = routineAccessService.requireViewableRoutine(routineId, user);
            return summary(routine, user);
        });
    }

    public Summary vote(String email, Long routineId, Long categoryId) {
        countIfDue(routineId);

        return transaction.execute(status -> {
            User user = requireUser(email);

            if (categoryId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a category");
            }

            Routine routine = routineRepository.findByIdForUpdate(routineId)
                    .filter(found -> routineAccessService.canViewRoutine(found, user))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routine not found"));

            if (!openToVotes(routine)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only public routines are categorized by the community");
            }

            Category category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));

            CategoryVoting voting = votingRepository.findByRoutineId(routineId).orElse(null);
            Instant now = Instant.now();

            if (voting != null && (voting.isClosed() || !now.isBefore(voting.getClosesAt()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Voting for this routine's category has closed");
            }

            if (voteRepository.existsByRoutineIdAndUserId(routineId, user.getId())) {
                throw alreadyVoted();
            }

            boolean liked = likedRoutineRepository.findByUserIdAndRoutineId(user.getId(), routineId).isPresent();
            boolean rated = routineRatingRepository.findByUserIdAndRoutineId(user.getId(), routineId).isPresent();

            if (!liked || !rated) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        (!liked && !rated ? "Like and rate" : !liked ? "Like" : "Rate")
                                + " this routine before voting for its category");
            }

            if (voting == null) {
                voting = new CategoryVoting();
                voting.setRoutine(routine);
                voting.setOpenedAt(now);
                voting.setClosesAt(now.plus(Duration.ofDays(VOTING_DAYS)));
                votingRepository.saveAndFlush(voting);
            }

            CategoryVote vote = new CategoryVote();
            vote.setRoutine(routine);
            vote.setUser(user);
            vote.setCategory(category);
            vote.setCreatedAt(now);

            try {
                voteRepository.saveAndFlush(vote);
            } catch (DataIntegrityViolationException exception) {
                throw alreadyVoted();
            }

            return summary(routine, user);
        });
    }

    // Counts every period that has ended; also run on each visit, so a count is never late for anyone.
    @Scheduled(fixedDelayString = "${app.category-voting.count-every-ms:600000}", initialDelay = 60000)
    public void finalizeDueVotings() {
        for (Long routineId : votingRepository.findDueRoutineIds(Instant.now())) {
            try {
                countIfDue(routineId);
            } catch (RuntimeException exception) {
                log.warn("Counting the category votes of routine {} failed; it is tried again later", routineId, exception);
            }
        }
    }

    // Counts the routine's votes once its period has ended, in a transaction of its own.
    private void countIfDue(Long routineId) {
        countTransaction.executeWithoutResult(status -> {
            CategoryVoting voting = votingRepository.findByRoutineId(routineId).orElse(null);

            if (voting == null || voting.isClosed() || Instant.now().isBefore(voting.getClosesAt())) {
                return;
            }

            Routine routine = routineRepository.findByIdForUpdate(routineId).orElse(null);
            // Read again under the routine's lock: another count may have just finished.
            voting = votingRepository.findByRoutineId(routineId).orElse(null);

            if (routine == null || voting == null || voting.isClosed()) {
                return;
            }

            Category winner = winner(routine, tally(routineId));

            if (winner != null) {
                routine.setCategory(winner.getName());
                routineRepository.saveAndFlush(routine);
            }

            voting.setClosedAt(Instant.now());
            voting.setWinnerCategory(winner);
            voting.setWinnerName(winner == null ? null : winner.getName());
            votingRepository.saveAndFlush(voting);
        });
    }

    // The most votes; a tie keeps the current category if it leads, else the first in the list.
    private Category winner(Routine routine, Map<Category, Long> tally) {
        long most = tally.values().stream().mapToLong(Long::longValue).max().orElse(0);

        if (most == 0) {
            return null;
        }

        List<Category> leaders = tally.entrySet().stream()
                .filter(entry -> entry.getValue() == most)
                .map(Map.Entry::getKey)
                .sorted(ORDER)
                .toList();
        String current = routine.getCategory() == null ? "" : routine.getCategory().trim().toLowerCase(Locale.ROOT);

        return leaders.stream()
                .filter(category -> category.getNameKey().equals(current))
                .findFirst()
                .orElse(leaders.get(0));
    }

    private static final Comparator<Category> ORDER = Comparator
            .comparing(Category::getPosition)
            .thenComparing(Category::getId);

    private Map<Category, Long> tally(Long routineId) {
        Map<Long, Long> counts = new HashMap<>();
        voteRepository.countByCategory(routineId).forEach(row -> counts.put((Long) row[0], (Long) row[1]));

        Map<Category, Long> tally = new HashMap<>();
        categoryRepository.findAllById(counts.keySet()).forEach(category -> tally.put(category, counts.get(category.getId())));

        return tally;
    }

    private Summary summary(Routine routine, User user) {
        CategoryVoting voting = votingRepository.findByRoutineId(routine.getId()).orElse(null);
        Map<Category, Long> tally = tally(routine.getId());

        List<Result> results = new ArrayList<>();
        tally.entrySet().stream()
                .sorted(Map.Entry.<Category, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey(ORDER)))
                .forEach(entry -> results.add(new Result(entry.getKey().getId(), entry.getKey().getName(), entry.getValue())));

        Choice myVote = voteRepository.findByRoutineIdAndUserId(routine.getId(), user.getId())
                .map(vote -> new Choice(vote.getCategory().getId(), vote.getCategory().getName()))
                .orElse(null);
        boolean liked = likedRoutineRepository.findByUserIdAndRoutineId(user.getId(), routine.getId()).isPresent();
        boolean rated = routineRatingRepository.findByUserIdAndRoutineId(user.getId(), routine.getId()).isPresent();
        boolean closed = voting != null && voting.isClosed();

        Choice winner = null;

        if (closed && voting.getWinnerCategory() != null) {
            winner = new Choice(voting.getWinnerCategory().getId(), voting.getWinnerCategory().getName());
        }

        String reason = !openToVotes(routine) ? "PRIVATE"
                : closed ? "CLOSED"
                : myVote != null ? "ALREADY_VOTED"
                : !liked || !rated ? "LIKE_AND_RATE"
                : null;

        return new Summary(
                voting == null ? "NOT_STARTED" : closed ? "CLOSED" : "OPEN",
                VOTING_DAYS,
                voting == null ? null : voting.getClosesAt(),
                voting == null ? null : voting.getClosedAt(),
                results.stream().mapToLong(Result::votes).sum(),
                results,
                winner,
                myVote,
                liked,
                rated,
                reason == null,
                reason
        );
    }

    // Votes are taken only where everyone may see the routine.
    private static boolean openToVotes(Routine routine) {
        return routine.isPublic() && !routine.isHiddenByAdmin() && !routine.isDeleted();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private static ResponseStatusException alreadyVoted() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "You have already voted for this routine's category");
    }
}
