package net.demilich.metastone.game.behaviour.heuristic;

import net.demilich.metastone.game.Attribute;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.entities.minions.Minion;

public class ChenWeightedHeuristic implements IGameStateHeuristic {

	private float[] weights;

	public ChenWeightedHeuristic(float[] weights) {
		this.weights = weights;
		// get weights from a weight file
		// and initialize the arg blow
	}

	private float calculateMinionScore(Minion minion) {
		float minionScore = minion.getAttack() + minion.getHp();
		float baseScore = minionScore;
		if (minion.hasAttribute(Attribute.FROZEN)) {
			return minion.getHp() * weights[8];
		}
		if (minion.hasAttribute(Attribute.TAUNT)) {
			minionScore +=  weights[9];
		}
		if (minion.hasAttribute(Attribute.WINDFURY)) {
			minionScore += minion.getAttack() * weights[10];
		}
		if (minion.hasAttribute(Attribute.DIVINE_SHIELD)) {
			minionScore +=  weights[11] * baseScore;
		}
		if (minion.hasAttribute(Attribute.SPELL_DAMAGE)) {
			minionScore += minion.getAttributeValue(Attribute.SPELL_DAMAGE) * weights[12];
		}
		if (minion.hasAttribute(Attribute.ENRAGED)) {
			minionScore +=  weights[13];
		}
		if (minion.hasAttribute(Attribute.STEALTH)) {
			minionScore +=  weights[14];
		}
		if (minion.hasAttribute(Attribute.UNTARGETABLE_BY_SPELLS)) {
			minionScore +=  weights[15] * baseScore;
		}

		return minionScore;
	}

	@Override
	public double getScore(GameContext context, int playerId) {
		float score = 0;
		Player player = context.getPlayer(playerId);
		Player opponent = context.getOpponent(player);
		if (player.getHero().isDestroyed()) {
			return Float.NEGATIVE_INFINITY;
		}
		if (opponent.getHero().isDestroyed()) {
			return Float.POSITIVE_INFINITY;
		}
		float ownHp = player.getHero().getHp() * weights[0] + player.getHero().getArmor() * weights[1];
		float opponentHp = opponent.getHero().getHp() * weights[2] + opponent.getHero().getArmor() * weights[3];
		score += ownHp - opponentHp;

		score += player.getHand().getCount() * weights[4];
		score -= opponent.getHand().getCount() * weights[5];
		score += player.getMinions().size() * weights[6];
		score -= opponent.getMinions().size() * weights[7];
		for (Minion minion : player.getMinions()) {
			score += calculateMinionScore(minion);
		}
		for (Minion minion : opponent.getMinions()) {
			score -= calculateMinionScore(minion);
		}

		return score;
	}

	@Override
	public void onActionSelected(GameContext context, int playerId) {
	}

}
