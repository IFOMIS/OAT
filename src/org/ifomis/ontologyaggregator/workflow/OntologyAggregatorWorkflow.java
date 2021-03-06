package org.ifomis.ontologyaggregator.workflow;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import org.apache.commons.mail.EmailException;
import org.apache.log4j.Logger;
import org.ifomis.ontologyaggregator.integration.HDOTExtender;
import org.ifomis.ontologyaggregator.notifications.EmailSender;
import org.ifomis.ontologyaggregator.recommendation.Recommendation;
import org.ifomis.ontologyaggregator.recommendation.RecommendationFilter;
import org.ifomis.ontologyaggregator.recommendation.RecommendationGenerator;
import org.ifomis.ontologyaggregator.search.SearchEngine;
import org.ifomis.ontologyaggregator.util.Configuration;
import org.ifomis.ontologyaggregator.util.StatisticsPrinter;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import uk.ac.ebi.ontocat.OntologyTerm;

/**
 * Implements the workflow of the Ontology Aggregator. Two underspecified
 * methods that correspond to the integration with the interface.
 * 
 * @author Nikolina
 * 
 */
public abstract class OntologyAggregatorWorkflow {

	private static final Logger log = Logger
			.getLogger(OntologyAggregatorWorkflow.class);

	private long start;
	private String term;

	private EmailSender mailSender;

	private SearchEngine se;

	private List<Recommendation> recommendations;

	private List<Recommendation> potentialRecommendations;

	public OntologyAggregatorWorkflow() throws Exception {
		this.start = System.currentTimeMillis();

		this.mailSender = new EmailSender();
		// 0. load configuration
		Configuration.getInstance();
	}

	public void start(List<String> terms, String userId, boolean userRights)
			throws Exception {

		boolean askForNewInput = false;

		for (String term : terms) {
			this.term = term;

			log.info("Search for term " + term + " in BioPortal");
			// 1. search term
			if (searchTerm()) {
				log.info("list of hits is empty or no paths were extracted");
				mailSender
						.sendMail(
								term + " NO RESULTS RETRIEVED FROM BioPortal",
								"BioPortal has retrived no results for the term\" "
										+ term
										+ "\"\n or the server is not responding");
				askForNewInput = true;
				continue;
			}

			// 2. generate recommendations
			RecommendationGenerator rg = new RecommendationGenerator(term,
					se.getRestrictedBps(), start);

			int returnCode = rg.generateRecommendations(se.getListOfPaths());

			if (returnCode == 0) {
				mailSender.sendMail(
						term + ": OAT SHOULD NOT BE EVOKED",
						"searched term:" + term + "\nThe concept: "
								+ rg.getMatchedClass()
								+ " is already contained in HDOT");
						
				StatisticsPrinter.printFinalTimeAndLogLocations(start, term);
				return;
			} else if (returnCode == 2) {
				mailSender
						.sendMail(
								term
										+ ": THE TERM WILL BE INCLUDED IN HDOT SOON",
								"searched term:"
										+ term
										+ "\nThe concept will be soon included in HDOT");
				StatisticsPrinter.printFinalTimeAndLogLocations(start, term);
				return;
			}

			// 3. sort the recommendations
			fiterGeneratedRecommendations(rg);

			if (recommendations.isEmpty()) {
				if (potentialRecommendations.isEmpty()) {
					log.info("NO RECOMMENDATIONS ARE GENERATED FOR THE TERM: "
							+ term);
					break;
				} else {
					log.info("A MATCHED CONCEPT WAS FOUND BUT THE INTEGRATION IN HDOT IS NOT YET POSSIBLE. THE CURATORS ARE INFORMED.");
					break;
				}
			}
			while (!recommendations.isEmpty()) {

				// 4. display recommendation to the user
				Recommendation topRecommendation = recommendations.get(0);
				displayRecommendation(topRecommendation);

				// 5. check the user input
				boolean confirmed = checkUserInput();

				// 6. extend HDOT
				if (confirmed) {
					HDOTExtender hdotExtender = new HDOTExtender(
							rg.getOntologyService(), userId, userRights);
					hdotExtender.integrarteHitInHDOT(topRecommendation);
					break;
				} else {
					mailSender.sendMail(term
							+ " THE USER REJECTED THE RECOMMENDATION",
							topRecommendation.toString());
					recommendations.remove(0);
				}
			}
		}
		if (askForNewInput) {
			log.info("\n ***Please check the spelling of the term you search or try with a synonym.***");
		}
		StatisticsPrinter.printFinalTimeAndLogLocations(start, term);
	}

	/**
	 * Searches term in BioPortal
	 * 
	 * @return
	 * @throws Exception
	 */
	private boolean searchTerm() throws Exception {
		IRI fileWithOntologies = Configuration.ONTO_IDS_FILE;
		se = new SearchEngine(fileWithOntologies);
		return se.searchTermInBioPortal(term);
	}

	/**
	 * @return a boolean array in the first position the value if the
	 *         recommendation was accepted is contained and in the second
	 *         position whether the subClasses should be integrated too.
	 */
	public abstract boolean checkUserInput();

	/**
	 * @param recommendation
	 *            the recommendation that will be shown the user
	 */
	public abstract void displayRecommendation(Recommendation recommendation);

	private void fiterGeneratedRecommendations(RecommendationGenerator rg)
			throws OWLOntologyCreationException, OWLOntologyStorageException,
			FileNotFoundException, IOException, EmailException {
		RecommendationFilter rf = new RecommendationFilter(term,
				rg.getListOfRecommendations(),
				rg.getListOfRecsPossibleInCoreOfHDOT(),
				rg.getListImportedNotLeafMatches(),
				rg.getListOfInCoreNotLeafMatches());

		rf.checkValidRecommendations();

		recommendations = rf.getValidRecommendations();
		potentialRecommendations = rf.getPotentialRecommendations();
	}
}