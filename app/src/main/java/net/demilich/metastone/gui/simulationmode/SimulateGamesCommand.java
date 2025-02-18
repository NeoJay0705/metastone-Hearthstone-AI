package net.demilich.metastone.gui.simulationmode;

import java.util.Random;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Collections;
import java.io.*;
import java.lang.CloneNotSupportedException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.demilich.metastone.game.behaviour.ChenGreedyOptimizeMove;
import net.demilich.metastone.game.behaviour.heuristic.ChenWeightedHeuristic;
import net.demilich.nittygrittymvc.Notification;
import net.demilich.nittygrittymvc.SimpleCommand;
import net.demilich.nittygrittymvc.interfaces.INotification;
import net.demilich.metastone.GameNotification;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.decks.DeckFormat;
import net.demilich.metastone.game.logic.GameLogic;
import net.demilich.metastone.game.gameconfig.GameConfig;
import net.demilich.metastone.game.gameconfig.PlayerConfig;
import net.demilich.metastone.utils.Tuple;

import net.demilich.metastone.game.statistics.GameStatistics;
import net.demilich.metastone.game.statistics.Statistic;

public class SimulateGamesCommand extends SimpleCommand<GameNotification> {

	private class IndividualTuple{
		float[] weights;
		float fitness;
	}

	private class PlayGameTask implements Callable<Void> {

		private final GameConfig gameConfig;

		public PlayGameTask(GameConfig gameConfig) {
			this.gameConfig = gameConfig;
		}

		@Override
		public Void call() throws Exception {
			
			//float[] weights = {1, 1, 1, 1, 3, 3, 2, 2, 1, 2, 0.5f, 1.5f, 1, 1, 1, 1.5f};
			
		
			PlayerConfig playerConfig1 = gameConfig.getPlayerConfig1();
			PlayerConfig playerConfig2 = gameConfig.getPlayerConfig2();
			
			//playerConfig1.setBehaviour(new ChenGreedyOptimizeMove(new ChenWeightedHeuristic(weights)));
			
			Player player1 = new Player(playerConfig1);
			Player player2 = new Player(playerConfig2);
			
			DeckFormat deckFormat = gameConfig.getDeckFormat();

			GameContext newGame = new GameContext(player1, player2, new GameLogic(), deckFormat);
			newGame.play();

			onGameComplete(gameConfig, newGame);
			newGame.dispose();

			return null;
		}

	}

	private static Logger logger = LoggerFactory.getLogger(SimulateGamesCommand.class);
	private int gamesCompleted;
	private long lastUpdate;

	private SimulationResult result;
	private GameConfig configClone;

	@Override
	public void execute(INotification<GameNotification> notification) {
		
		final GameConfig gameConfig = (GameConfig) notification.getBody();
		
		result = new SimulationResult(gameConfig);
		
		
		
		try {
			configClone = (GameConfig) gameConfig.clone();
		} catch (CloneNotSupportedException e) {}
		
		//GameStatistics player1 = result.getPlayer1Stats().clone();
		//GameStatistics player2 = result.getPlayer2Stats().clone();

		gamesCompleted = 0;

		Thread t = new Thread(new Runnable() {

			@Override
			public void run() {
			
				// initial
				List<float[]> population = new ArrayList<float[]>();
				List<IndividualTuple> individualList = new ArrayList<IndividualTuple>();
				int generation = 10;
				int populationSize = 5;
				int weightSize = 16;
				float standardDeviation = 0.5f;
				float mean = 1f;
				float ALPHA = 0.5f;
				float mutationRate = 0.1f;
				
				Random r = new Random();
				
				
				//try {
				//File f1 = new File("test2.txt");
				//FileOutputStream fop = new FileOutputStream(f1);
				//OutputStreamWriter writer = new OutputStreamWriter(fop, "UTF-8");
				for (int u=1; u<=generation; u++){
				
					// initial
					population = new ArrayList<float[]>();
					individualList = new ArrayList<IndividualTuple>();
				
					//initialize
					for (int j=1; j<= populationSize; j++){
						// result = new SimulationResult(initialConfig);
						
						float[] weights = new float[weightSize];
						
						for (int k=0; k< weightSize; k++){
							float w = (float) r.nextGaussian() * standardDeviation + mean;
							weights[k] = w;
						}
			
						population.add(weights);
						
					}
					
					// for each individual, do evaluation
					for (float[] weights : population){
						try {
							result = new SimulationResult((GameConfig) configClone.clone());
						} catch (CloneNotSupportedException e) {}
					
						PlayerConfig playerConfig1 = gameConfig.getPlayerConfig1();
						playerConfig1.setBehaviour(new ChenGreedyOptimizeMove(new ChenWeightedHeuristic(weights)));
						
						int cores = Runtime.getRuntime().availableProcessors();
						logger.info("Starting simulation on " + cores + " cores");
						ExecutorService executor = Executors.newFixedThreadPool(cores);
						// ExecutorService executor =
						// Executors.newSingleThreadExecutor();

						List<Future<Void>> futures = new ArrayList<Future<Void>>();
						// send initial status update
						Tuple<Integer, Integer> progress = new Tuple<>(0, gameConfig.getNumberOfGames());
						getFacade().sendNotification(GameNotification.SIMULATION_PROGRESS_UPDATE, progress);

						// queue up all games as tasks
						lastUpdate = System.currentTimeMillis();
			
						for (int g = 0; g < gameConfig.getNumberOfGames(); g++) {
							PlayGameTask task = new PlayGameTask(gameConfig);
							Future<Void> future = executor.submit(task);
							futures.add(future);
						}

						// executor.shutdown();
						boolean completed = false;
						while (!completed) {
							completed = true;
							for (Future<Void> future : futures) {
								if (!future.isDone()) {
									completed = false;
									continue;
								}
								try {
									future.get();
								} catch (InterruptedException | ExecutionException e) {
									logger.error(ExceptionUtils.getStackTrace(e));
									e.printStackTrace();
									System.exit(-1);
								}
							}
							futures.removeIf(future -> future.isDone());
							try {
								Thread.sleep(50);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}

						result.calculateMetaStatistics();
						GameStatistics player1stats = result.getPlayer1Stats();
						// winRate == fitnessScore
						Object winRate = player1stats.get(Statistic.WIN_RATE);
						float fitness = Float.parseFloat(winRate.toString().replace("%", ""));
						IndividualTuple individual = new IndividualTuple();
						individual.weights = weights;
						individual.fitness = fitness;
						individualList.add(individual);
						
						// IMPORTANT!
						player1stats.set(Statistic.WIN_RATE, "0");
						// player1stats.set(Statistic.GAMES_WON, "0");
						// player1stats.set(Statistic.GAMES_LOST, "0");
						
					}
					// sorting
					Float[] fitness = new Float[populationSize];
					for (IndividualTuple individual: individualList) {
						int index = individualList.indexOf(individual);
						fitness[index] = individual.fitness;
					}
					List<IndividualTuple> sortedIndividualList = new ArrayList<IndividualTuple>();
					for (IndividualTuple individual: individualList) {
						float best = Collections.max(Arrays.asList(fitness));
						int index = -1;
						for (int f = 0; f < fitness.length; f++)
							if (fitness[f] == best)
								index = f;
						sortedIndividualList.add(individualList.get(index));
						fitness[index] = -1f;
					}
					
					// write generation state to file
					
					//try{
					try {
						File f1 = new File("test2.txt");
						FileOutputStream fop = new FileOutputStream(f1, true);
						OutputStreamWriter writer = new OutputStreamWriter(fop, "UTF-8");
						writer.append("Generation " + Integer.toString(u) + "\n");
						for (IndividualTuple individual: sortedIndividualList){
							writer.append("W ");
							for (float w : individual.weights){
								writer.append(Float.toString(w) + " ");
							}
							writer.append("\n");
							writer.append("F " + Float.toString(individual.fitness) + "\n");
						}
						
						writer.close();
						fop.close();
						} catch (IOException e) {
						}
					
					// parents selection and crossover using DE
					List<IndividualTuple> selectedIndividualList = sortedIndividualList.subList(0, populationSize/2);
					List<IndividualTuple> offsprings = new ArrayList<IndividualTuple>();
					for (int j = 0; j < populationSize / 2; j++){
						int parent1index = r.nextInt(populationSize / 2);
						int parent2index = r.nextInt(populationSize / 2);
						IndividualTuple parent1 = selectedIndividualList.get(parent1index);
						IndividualTuple parent2 = selectedIndividualList.get(parent2index);
						float[] shift = new float[weightSize];
						float[] offspringWeight = new float[weightSize];
						for (int k = 0; k < weightSize; k++){
							shift[k] = ALPHA * (parent1.weights[k] - parent2.weights[k]);
							offspringWeight[k] = selectedIndividualList.get(j).weights[k] + shift[k];
						}
						IndividualTuple offspring = new IndividualTuple();
						offspring.weights = offspringWeight;
						offspring.fitness = 0f;
						offsprings.add(offspring);
					}
					for (IndividualTuple offspring: offsprings){
						selectedIndividualList.add(offspring);
					}
					
					// Mutation
					if (r.nextFloat() < mutationRate){
						for (IndividualTuple individual: selectedIndividualList){
							for (int j=0; j<weightSize; j++){
								individual.weights[j] += (float) r.nextGaussian() * standardDeviation / 10 + mean / 10;
							}
						}	
					}
					
					
					
				}// for generation
				//writer.close();
				//fop.close();
				//}// try
				//catch(IOException e) {}
				/*
				
				float[] weights = {1, 1, 1, 1, 3, 3, 2, 2, 1, 2, 0.5f, 1.5f, 1, 1, 1, 1.5f};
				PlayerConfig playerConfig1 = gameConfig.getPlayerConfig1();
			
				playerConfig1.setBehaviour(new ChenGreedyOptimizeMove(new ChenWeightedHeuristic(weights)));
				// do
			
				int cores = Runtime.getRuntime().availableProcessors();
				logger.info("Starting simulation on " + cores + " cores");
				ExecutorService executor = Executors.newFixedThreadPool(cores);
				// ExecutorService executor =
				// Executors.newSingleThreadExecutor();

				List<Future<Void>> futures = new ArrayList<Future<Void>>();
				// send initial status update
				Tuple<Integer, Integer> progress = new Tuple<>(0, gameConfig.getNumberOfGames());
				getFacade().sendNotification(GameNotification.SIMULATION_PROGRESS_UPDATE, progress);

				// queue up all games as tasks
				lastUpdate = System.currentTimeMillis();
				
				for (int g = 0; g < gameConfig.getNumberOfGames(); g++) {
					PlayGameTask task = new PlayGameTask(gameConfig);
					Future<Void> future = executor.submit(task);
					futures.add(future);
				}

				executor.shutdown();
				boolean completed = false;
				while (!completed) {
					completed = true;
					for (Future<Void> future : futures) {
						if (!future.isDone()) {
							completed = false;
							continue;
						}
						try {
							future.get();
						} catch (InterruptedException | ExecutionException e) {
							logger.error(ExceptionUtils.getStackTrace(e));
							e.printStackTrace();
							System.exit(-1);
						}
					}
					futures.removeIf(future -> future.isDone());
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				result.calculateMetaStatistics();
				GameStatistics player1stats = result.getPlayer1Stats();
				// winRate == fitnessScore
				Object winRate = player1stats.get(Statistic.WIN_RATE);
				float fitness = Float.parseFloat(winRate.toString().replace("%", ""));
				
				try {
					File f1 = new File("test2.txt");
					FileOutputStream fop = new FileOutputStream(f1);
					OutputStreamWriter writer = new OutputStreamWriter(fop, "UTF-8");
					writer.append(winRate.toString().replace("%", ""));
					writer.close();
					fop.close();
				} catch (IOException e) {
				}
				
				// 
				// select parents according to fitness score
				// population = population[:populationSize / 2]
				// recombine parents
				// mutate offsprings
				// copy evaluation
				// evironment selection
				// while(Stop condition)
				*/
				
				getFacade().sendNotification(GameNotification.SIMULATION_RESULT, result);
				logger.info("Simulation finished");

			}
		});
		t.setDaemon(true);
		t.start();
	}

	private void onGameComplete(GameConfig gameConfig, GameContext context) {
		long timeStamp = System.currentTimeMillis();
		gamesCompleted++;
		if (timeStamp - lastUpdate > 100) {
			lastUpdate = timeStamp;
			Tuple<Integer, Integer> progress = new Tuple<>(gamesCompleted, gameConfig.getNumberOfGames());
			Notification<GameNotification> updateNotification = new Notification<>(GameNotification.SIMULATION_PROGRESS_UPDATE, progress);
			getFacade().notifyObservers(updateNotification);
		}
		synchronized (result) {
			result.getPlayer1Stats().merge(context.getPlayer1().getStatistics());
			result.getPlayer2Stats().merge(context.getPlayer2().getStatistics());
		}
	}

}
