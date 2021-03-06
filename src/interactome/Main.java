/*
 * Interaction Analysis v3
 * written by Ryohei Suzuki (rsuz.gpat@mri.tmd.ac.jp)
 *
 */

package interactome;

import interactome.analysis.Analysis;
import interactome.data.BioDB;

public class Main {
	public static void main(String[] args) {
		// load options
		Option option = Option.createInstance(args);
		if (option == null) {
			return;
		}
		
		// initialize logger
		Logger.initialize();
		
		// load settings.properties
		if (!option.loadSettingFile()) {
			Logger.close();
			return;
		}
		
		// initialize BioDB
		BioDB bioDB = BioDB.createInstance();
		if (bioDB == null) {
			Logger.close();
			return;
		}
		
		// start analysis
		Analysis analysis = Analysis.createInstance();
		
		if (!analysis.run()) {
			Logger.close();
			return;
		}
		
		// output analysis results
		if (!analysis.outputResults()) {
			Logger.close();
			return;
		}
		
		Logger.logf("\nAnalysis finished.");
		Logger.close();
	}
}
