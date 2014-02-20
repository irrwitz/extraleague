package ch.squix.extraleague.model.ranking;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import ch.squix.extraleague.model.match.Match;
import ch.squix.extraleague.model.match.MatchDateComparator;
import ch.squix.extraleague.model.match.MatchUtil;
import ch.squix.extraleague.model.match.Matches;
import ch.squix.extraleague.model.match.PlayerCombo;
import ch.squix.extraleague.model.match.PlayerMatchResult;
import ch.squix.extraleague.model.match.Position;
import ch.squix.extraleague.rest.games.GameResource;

public class RankingService {
	
	private static final Logger log = Logger.getLogger(GameResource.class.getName());

	public static void calculateRankings() {
		List<Match> matchesList = ofy().load().type(Match.class).list();
		Matches matches = new Matches();
		matches.setMatches(matchesList);
		Map<Long, List<Match>> gameMap = new HashMap<>();
		for (Match match : matches.getMatches()) {
			List<Match> gameMatches = gameMap.get(match.getGameId());
			if (gameMatches == null) {
				gameMatches = new ArrayList<>();
				gameMap.put(match.getGameId(), gameMatches);
			}
			gameMatches.add(match);
		}
		Map<String, PlayerRanking> playerRankingMap = new HashMap<>();
		Map<String, Map<String, PlayerCombo>> partnerMap = new HashMap<>();
		Map<String, Map<String, PlayerCombo>> opponentMap = new HashMap<>();
		for (Map.Entry<Long, List<Match>> entry : gameMap.entrySet()) {
			Map<String, List<String>> scoreMap = new HashMap<>();
			Map<String, Integer> winMap = new HashMap<>();
			for (Match match : entry.getValue()) {
			        //clearWrongCharacters(match);
				if (match.getEndDate() != null) {
				    List<PlayerMatchResult> playerMatches = MatchUtil.getPlayerMatchResults(match);
				    for (PlayerMatchResult playerMatch : playerMatches) {
				        PlayerRanking playerRanking = getRanking(playerMatch.getPlayer(), playerRankingMap);
				        if (playerMatch.hasWon()) {
				            playerRanking.increaseGamesWon();
				            Integer matchesWon = winMap.get(playerRanking.getPlayer());
				            if (matchesWon == null) {
				                matchesWon = 0;
				            }
				            winMap.put(playerRanking.getPlayer(), matchesWon + 1);

				        } else {
				            playerRanking.increaseGamesLost();
				        }
				        playerRanking.setGoalsMade(playerRanking.getGoalsMade() + playerMatch.getGoalsMade());
				        playerRanking.setGoalsGot(playerRanking.getGoalsGot() + playerMatch.getGoalsGot());
				        addMatchScore(scoreMap, playerMatch);
				        updatePlayerCombo(partnerMap, opponentMap, playerMatch);
				    }

				}
			}
			calculateMatchBadges(scoreMap, playerRankingMap);
			addStrikeBadge(winMap, playerRankingMap);
		} 
		calculatePlayerBadges(matches, playerRankingMap);
		log.info("partnerMap contains " + partnerMap.size() + " entries");
		log.info("opponentMap contains " + opponentMap.size() + " entries");
		calculatePartnerAndOpponents(playerRankingMap, partnerMap, opponentMap);
		//ofy().save().entities(matches);
		List<PlayerRanking> rankings = filterFirstPlayers(playerRankingMap.values());
		Collections.sort(rankings, new Comparator<PlayerRanking>() {

			@Override
			public int compare(PlayerRanking o1, PlayerRanking o2) {
				int result = o2.getSuccessRate().compareTo(o1.getSuccessRate());
				if (result == 0) {
					return o2.getGoalRate().compareTo(o1.getGoalRate());
				}
				return result;
			}
			
		});
		int index = 1;
		for (PlayerRanking ranking : rankings) {
			ranking.setRanking(index);
			index++;
		}
		calculateBadges(rankings);
		Ranking ranking = new Ranking();
		ranking.setCreatedDate(new Date());
		ranking.setPlayerRankings(rankings);
		ofy().save().entities(ranking);

	}

	private static void calculatePlayerBadges(Matches matches, Map<String, PlayerRanking> playerRankingMap) {
		MatchDateComparator matchDateComparator = new MatchDateComparator();
		for (String player : matches.getPlayers()) {
			PlayerRanking ranking = playerRankingMap.get(player);
			int victoriesInARow = 0;
			int maxVictoriesInARow = 0;
			List<Match> matchesByPlayer = matches.getMatchesByPlayer(player);
			Collections.sort(matchesByPlayer, matchDateComparator);
			Map<Position, Integer> positionMap = new HashMap<>();
			positionMap.put(Position.Offensive, 0);
			positionMap.put(Position.Defensive, 0);

			for (Match match : matchesByPlayer) {
				PlayerMatchResult playerMatch = MatchUtil.getPlayerMatchResult(match, player);
				if (playerMatch.hasWon()) {
					victoriesInARow++;
					Position position = playerMatch.getPosition();
					Integer positionCount = positionMap.get(position) + 1;
					positionMap.put(position, positionCount);
				} else {
					victoriesInARow = 0;
				}
				maxVictoriesInARow = Math.max(victoriesInARow, maxVictoriesInARow);
			}

			if (maxVictoriesInARow > 4) {
				ranking.getBadges().add(maxVictoriesInARow + "xSlam");
			}
			Integer offensiveCount = positionMap.get(Position.Offensive);
			Integer defensiveCount = positionMap.get(Position.Defensive);
			Double offensiveRate = 1.0 * offensiveCount / (offensiveCount + defensiveCount);
			if (offensiveCount > defensiveCount) {
				ranking.setBestPosition(Position.Offensive);
				ranking.setBestPositionRate(offensiveRate);
			} else if (offensiveCount == defensiveCount) {
				ranking.setBestPosition(Position.Omnivore);
				ranking.setBestPositionRate(0.5d);
			} else {
				ranking.setBestPosition(Position.Defensive);
				ranking.setBestPositionRate(1 - offensiveRate);
			}
		}
	}

	private static void calculatePartnerAndOpponents(Map<String, PlayerRanking> playerRankingMap,
			Map<String, Map<String, PlayerCombo>> partnerMap, Map<String, Map<String, PlayerCombo>> opponentMap) {
		for (String player : playerRankingMap.keySet()) {
			PlayerRanking ranking = playerRankingMap.get(player);
			Map<String, PlayerCombo> partnerComboMap = partnerMap.get(player);
			log.info("PartnerMap for player " + player + " contains " + partnerComboMap.size());
			PlayerComboComparator comparator = new PlayerComboComparator();

			List<PlayerCombo> partners = new ArrayList<>(partnerComboMap.values());
			Collections.sort(partners, comparator);
			for (PlayerCombo partner : partners) {
				System.out.println(player +": " + partners.indexOf(partner) + ". " + partner.getCombo() + "/" 
						+ partner.getGamesWon() + "/" + partner.getGamesLost() + "/" + partner.getSuccessRate());
			}
			ranking.setBestPartner(partners.get(0).getCombo());
			ranking.setBestPartnerRate(partners.get(0).getSuccessRate());
			ranking.setWorstPartner(partners.get(partners.size() - 1).getCombo());
			ranking.setWorstPartnerRate(partners.get(partners.size() - 1).getSuccessRate());
			log.info("Setting " + partners.get(0).getPlayer());

			Map<String, PlayerCombo> opponentComboMap = opponentMap.get(player);

			List<PlayerCombo> opponents = new ArrayList<>(opponentComboMap.values());
			Collections.sort(opponents, comparator);
			for (PlayerCombo opponent : opponents) {
				System.out.println(player +": " + opponents.indexOf(opponent) + ". " + opponent.getCombo() + "/" 
						+ opponent.getGamesWon() + "/" + opponent.getGamesLost() + "/" + opponent.getSuccessRate());
			}
			ranking.setBestOpponent(opponents.get(opponents.size() - 1).getCombo());
			ranking.setBestOpponentRate(1 - opponents.get(opponents.size() - 1).getSuccessRate());
			ranking.setWorstOpponent(opponents.get(0).getCombo());
			ranking.setWorstOpponentRate(1 - opponents.get(0).getSuccessRate());
			
		}
	}

	private static void updatePlayerCombo(Map<String, Map<String, PlayerCombo>> partnerMap, Map<String, Map<String, PlayerCombo>> opponentMap,
			PlayerMatchResult playerMatch) {
		PlayerCombo partner = getPlayerCombo(partnerMap, playerMatch.getPlayer(), playerMatch.getPartner());
		if (playerMatch.hasWon()) {
			partner.increaseGamesWon();
		} else {
			partner.increaseGamesLost();
		}
		for (String opponentName : playerMatch.getOpponents()) {
			PlayerCombo opponent = getPlayerCombo(opponentMap, playerMatch.getPlayer(), opponentName);
			if (playerMatch.hasWon()) {
				opponent.increaseGamesLost();
			} else {
				opponent.increaseGamesWon();
			}
		}
	}

	private static PlayerCombo getPlayerCombo(Map<String, Map<String, PlayerCombo>> playerComboMap, String player,
			String comboName) {
		Map<String, PlayerCombo> map = playerComboMap.get(player);
		if (map == null) {
			map = new HashMap<>();
			playerComboMap.put(player, map);
		}
		PlayerCombo combo = map.get(comboName);
		if (combo == null) {
			combo = new PlayerCombo();
			combo.setPlayer(player);
			combo.setCombo(comboName);
			map.put(comboName, combo);
		}
		return combo;
	}

	private static void addStrikeBadge(Map<String, Integer> winMap, Map<String, PlayerRanking> playerRankingMap) {
		for (Map.Entry<String, Integer> entry : winMap.entrySet()) {
			if (entry.getValue() == 4) {
				PlayerRanking ranking = getRanking(entry.getKey(), playerRankingMap);
				ranking.getBadges().add("Strike");
			}
		}
	}
	private static void addMatchScore(Map<String, List<String>> scoreMap, PlayerMatchResult playerMatch) {
			List<String> score = scoreMap.get(playerMatch.getPlayer());
			if (score == null) {
				score = new ArrayList<>();
				scoreMap.put(playerMatch.getPlayer(), score);
			}
			score.add(playerMatch.getGoalsMade() + ":" + playerMatch.getGoalsGot());
	}

	private static List<PlayerRanking> filterFirstPlayers(Collection<PlayerRanking> values) {
		List<PlayerRanking> rankings = new ArrayList<>();
		for (PlayerRanking ranking : values) {
			if (ranking.getTotalGames() >=8) {
				rankings.add(ranking);
			}
		}
		return rankings;
	}

    private static void calculateMatchBadges(Map<String, List<String>> scoreMap, Map<String, PlayerRanking> playerRankingMap) {
    	 for(String player : scoreMap.keySet()) {
    		 List<String> scores = scoreMap.get(player);
    		 int numberOfFiveZeros = Collections.frequency(scores, "5:0");
    		 if (numberOfFiveZeros > 0) {
	    		 PlayerRanking ranking = getRanking(player, playerRankingMap);
	    		 ranking.getBadges().add(numberOfFiveZeros + "x5:0!");
    		 }
    	 }
	}


	private static void calculateBadges(List<PlayerRanking> rankings) {
		for (PlayerRanking ranking : rankings) {
			if (ranking.getRanking() == 1) {
				ranking.getBadges().add("King");
			}
			if (ranking.getRanking() == 2) {
				ranking.getBadges().add("Queen");
			}
			if (ranking.getRanking() == rankings.size()) {
				ranking.getBadges().add("Pawn");
			}
		}
	}

	private static PlayerRanking getRanking(String player, Map<String, PlayerRanking> playerRankingMap) {
		PlayerRanking ranking = playerRankingMap.get(player);
		if (ranking == null) {
			ranking = new PlayerRanking();
			ranking.setPlayer(player);
			ranking.setGamesWon(0);
			ranking.setGamesLost(0);
			playerRankingMap.put(player, ranking);
		}
		return ranking;
	}
	
	private static class PlayerComboComparator implements Comparator<PlayerCombo> {

		@Override
		public int compare(PlayerCombo o1, PlayerCombo o2) {
			int result = o2.getSuccessRate().compareTo(o1.getSuccessRate());
			if (result == 0) {
				return o2.getTotalGames().compareTo(o1.getTotalGames());
			}
			return result;
		}
		
	}

}
