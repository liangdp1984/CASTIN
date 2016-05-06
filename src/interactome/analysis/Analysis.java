package interactome.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import interactome.Logger;
import interactome.Option;
import interactome.data.BioDB;
import interactome.data.Gene;
import interactome.data.Interaction;
import interactome.input.GeneInput;
import interactome.input.Input;
import interactome.input.PairedEndInput;
import interactome.input.RefseqInput;
import interactome.input.SingleEndInput;

public class Analysis {
	private static Analysis _instance;
	public static Analysis createInstance() {
		Analysis._instance = new Analysis();
		return Analysis._instance;
	}
	public static Analysis getInstance() {
		return Analysis._instance;
	}
	
	public Input input;
	public DynamicParameters dp;
	public ResultsWriter rw;
	
	private Analysis() {
		this.input = null;
	}
	
	public boolean run() {
		// load input reads
		this.input = this.loadInput();
		if (this.input == null) {
			return false;
		}
		
		// calculate dynamic parameters
		this.dp = DynamicParameters.createInstance(this.input);
		if (!dp.calculateWeightedMappability()) {
			return false;
		}
		dp.selectParameterRefseqs();
		dp.checkLongUnmappableRefseqs();
		
		// execute bias correction
		BiasCorrector bc = BiasCorrector.createInstance(this.input, this.dp);
		bc.correctBias();
		bc.calculateRegressionResidues();
		
		// normalize expression sums
		this.normalize();
		
		// analyze interaction
		this.analyzeInteraction();
		
		return true;
	}
	
	public boolean outputResults() {
		this.rw = ResultsWriter.createInstance(this.input, this.dp);
		
		Logger.logf("\nwriting results.");
		
		// write Refseq and Symbol files.
		this.rw.writeRefseqFiles();
		this.rw.writeSymbolFiles();
		
		// write KEGGHPRD-related files.
		this.rw.writeKEGGHPRDFiles();
		
		// write heatmaps
		this.rw.drawHeatmaps();
		
		return true;
	}
	
	private Input loadInput() {
		Option option = Option.getInstance();
		
		Input input = null;
		if (option.input_type_paired) {
			input = new PairedEndInput();
		} else {
			input = new SingleEndInput();
		}
		
		if (!input.loadFile()) {
			return null;
		}
		
		return input;
	}
	
	private void normalize() {
		BioDB biodb = BioDB.getInstance();
		
		Logger.logf("\nnormalizing.");
		
		for (Map.Entry<String, Gene> entry : biodb.gene_db.entrySet()) {
			String entrez_id = entry.getKey();
			Gene gene = entry.getValue();
			
			RefseqInput[] refinputs = new RefseqInput[gene.variants.size()];
			for (int i=0; i<gene.variants.size(); i++) {
				refinputs[i] = input.refseq_inputs.get(gene.variants.get(i).refseq_id);
			}
			
			// accept refseq with maximum true_expression
			int maximum = 0;
			for (int i=1; i<gene.variants.size(); i++) {
				if (refinputs[maximum].true_expression < refinputs[i].true_expression) {
					maximum = i;
				}
			}
			
			GeneInput geneinput = input.gene_inputs.get(entrez_id);
			geneinput.representativeRefseq = gene.variants.get(maximum);
			geneinput.representativeRefseqInput = refinputs[maximum];
		}
		
		// normalize of cancer gene expressions
		// make sum of the middle 90% genes to 300,000 reads
		double[] expressions = new double[biodb.cancer_entrez_ids.length];
		for (int i=0; i<biodb.cancer_entrez_ids.length; i++) {
			expressions[i] = input.gene_inputs.get(biodb.cancer_entrez_ids[i]).representativeRefseqInput.true_expression;
		}
		Arrays.sort(expressions);
		double sum = 0;
		for (int i=(int)(expressions.length*0.95); i>expressions.length*0.05; i--) {
			sum += expressions[i];
		}
		if (sum == 0) {
			for (int i=0; i<expressions.length; i++) {
				sum += expressions[i];
			}
		}
		for (String entrez_id : biodb.cancer_entrez_ids) {
			GeneInput ginput = input.gene_inputs.get(entrez_id);
			ginput.normalizedExpression = ginput.representativeRefseqInput.true_expression * 300000.0 / sum;
		}

		// normalize of stromal gene expressions
		expressions = new double[biodb.stromal_refseq_ids.length];
		for (int i=0; i<biodb.stromal_entrez_ids.length; i++) {
			expressions[i] = input.gene_inputs.get(biodb.stromal_entrez_ids[i]).representativeRefseqInput.true_expression;
		}
		Arrays.sort(expressions);
		sum = 0;
		for (int i=(int)(expressions.length*0.95); i>expressions.length*0.05; i--) {
			sum += expressions[i];
		}
		if (sum == 0) {
			for (int i=0; i<expressions.length; i++) {
				sum += expressions[i];
			}
		}
		for (String entrez_id : biodb.stromal_entrez_ids) {
			GeneInput ginput = input.gene_inputs.get(entrez_id);
			ginput.normalizedExpression = ginput.representativeRefseqInput.true_expression * 300000.0 / sum;
		}
		Logger.logf("normalizing done.");
	}
	
	private void analyzeInteraction() {
		BioDB biodb = BioDB.getInstance();

		Logger.logf("\nanalyzing interactions.");
		
		for (Interaction interaction : biodb.interactions) {
			// select cancer-ligand
			for (Gene gene : interaction.ligand_cancer) {
				GeneInput ginput = input.gene_inputs.get(gene.entrez_id);
				if (interaction.ginput_ligand_cancer == null || 
					interaction.ginput_ligand_cancer.normalizedExpression < ginput.normalizedExpression)
					interaction.ginput_ligand_cancer = ginput;
			}
			// select stromal-ligand
			for (Gene gene : interaction.ligand_stroma) {
				GeneInput ginput = input.gene_inputs.get(gene.entrez_id);
				if (interaction.ginput_ligand_stroma == null || 
					interaction.ginput_ligand_stroma.normalizedExpression < ginput.normalizedExpression)
					interaction.ginput_ligand_stroma = ginput;
			}
			// select cancer-receptor
			for (Gene gene : interaction.receptor_cancer) {
				GeneInput ginput = input.gene_inputs.get(gene.entrez_id);
				if (interaction.ginput_receptor_cancer == null || 
					interaction.ginput_receptor_cancer.normalizedExpression < ginput.normalizedExpression)
					interaction.ginput_receptor_cancer = ginput;
			}
			// select stromal-receptor
			for (Gene gene : interaction.receptor_stroma) {
				GeneInput ginput = input.gene_inputs.get(gene.entrez_id);
				if (interaction.ginput_receptor_stroma == null || 
					interaction.ginput_receptor_stroma.normalizedExpression < ginput.normalizedExpression)
					interaction.ginput_receptor_stroma = ginput;
			}
		}

		/*
		 * KEGGHPRD_result
		 */
		for (Interaction interaction : biodb.interactions) {
			double exp_lig_cancer = 0;
			if (interaction.ginput_ligand_cancer != null)
				exp_lig_cancer = interaction.ginput_ligand_cancer.normalizedExpression;
			double exp_rec_cancer = 0;
			if (interaction.ginput_receptor_cancer != null)
				exp_rec_cancer = interaction.ginput_receptor_cancer.normalizedExpression;
			double exp_lig_stroma = 0;
			if (interaction.ginput_ligand_stroma != null)
				exp_lig_stroma = interaction.ginput_ligand_stroma.normalizedExpression;
			double exp_rec_stroma = 0;
			if (interaction.ginput_receptor_stroma != null)
				exp_rec_stroma = interaction.ginput_receptor_stroma.normalizedExpression;
			
			// sum of other ligand expression
			double sum_of_ligand_expression_for_same_receptor = 0;
			for (Interaction _inter : biodb.interactions) {
				if (interaction.receptor_symbol.equals(_inter.receptor_symbol)) {
					if (_inter.ginput_ligand_cancer != null)
						sum_of_ligand_expression_for_same_receptor += _inter.ginput_ligand_cancer.normalizedExpression;
					if (_inter.ginput_ligand_stroma != null)
						sum_of_ligand_expression_for_same_receptor += _inter.ginput_ligand_stroma.normalizedExpression;
				}
			}
			
			if (interaction.valid_cancer_to_stroma) {
				// cancer to stroma interaction average
				interaction.average_cancer2stroma = Math.sqrt(
					exp_lig_cancer * exp_rec_stroma
				);
				// ligand ratio
				if (exp_lig_cancer + exp_lig_stroma > 0) {
					interaction.ligand_ratio_cancer = exp_lig_cancer / (exp_lig_cancer + exp_lig_stroma);
					interaction.ligand_ratio_stroma = 1.0 - interaction.ligand_ratio_cancer;
				} else {
					interaction.ligand_ratio_cancer = -1;
					interaction.ligand_ratio_stroma = -1;
				}
			}
			if (interaction.valid_stroma_to_cancer) {
				// stroma to cancer interaction average
				interaction.average_stroma2cancer = Math.sqrt(
					exp_lig_stroma * exp_rec_cancer
				);
				// ligand ratio
				if (exp_lig_cancer + exp_lig_stroma > 0) {
					interaction.ligand_ratio_cancer = exp_lig_cancer / (exp_lig_cancer + exp_lig_stroma);
					interaction.ligand_ratio_stroma = 1.0 - interaction.ligand_ratio_cancer;
				} else {
					interaction.ligand_ratio_cancer = -1;
					interaction.ligand_ratio_stroma = -1;
				}
			}
			// count of the other ligands
			if (sum_of_ligand_expression_for_same_receptor > 0) {
				interaction.ligand_posession_for_same_receptor =
					(exp_lig_cancer + exp_lig_stroma) / sum_of_ligand_expression_for_same_receptor;
			}
			// receptor ratio
			if (exp_rec_cancer + exp_rec_stroma > 0) {
				interaction.receptor_ratio_stroma =
					exp_rec_stroma / (exp_rec_cancer + exp_rec_stroma);
				interaction.receptor_ratio_cancer = 1.0 - interaction.receptor_ratio_stroma;
			} else {
				interaction.receptor_ratio_stroma = -1;
				interaction.receptor_ratio_cancer = -1;
			}
		}
		
		/*
		 * KEGGHPRD_result_cancer_ligand
		 */		
		for (Interaction interaction : biodb.interactions) {
			// enumerate all receptor expressions
			double sum_of_receptor_expression_cancer = 0;
			double sum_of_receptor_expression_stroma = 0;
			for (Interaction _inter : biodb.interactions) {
				if (interaction.ligand_symbol.equals(_inter.ligand_symbol)) {
					if (_inter.ginput_receptor_cancer != null)
						sum_of_receptor_expression_cancer += _inter.ginput_receptor_cancer.normalizedExpression;
					if (_inter.ginput_receptor_stroma != null)
						sum_of_receptor_expression_stroma += _inter.ginput_receptor_stroma.normalizedExpression;
				}
			}
			
			// average
			if (interaction.ginput_ligand_cancer != null) { 
				interaction.cancer_ligand_average = Math.sqrt(
					(sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma)
					* interaction.ginput_ligand_cancer.normalizedExpression);
			}
			
			// receptor ratio
			if (sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma > 0)
				interaction.cancer_ligand_receptor_ratio = sum_of_receptor_expression_cancer / (sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma);
			else
				interaction.cancer_ligand_receptor_ratio = -1;
				
			// receptor ranking
			ArrayList<InteractionResult> results = new ArrayList<Analysis.InteractionResult>();
			HashSet<String> used = new HashSet<String>();
			for (Interaction _inter : biodb.interactions) {
				if (interaction.ligand_symbol.equals(_inter.ligand_symbol)) {
					double rec_expression = 0;
					if (_inter.ginput_receptor_cancer != null)
						rec_expression += _inter.ginput_receptor_cancer.normalizedExpression;
					if (_inter.ginput_receptor_stroma != null)
						rec_expression += _inter.ginput_receptor_stroma.normalizedExpression;
					if (!used.contains(_inter.receptor_symbol)) {
						results.add(new InteractionResult(_inter.receptor_symbol, rec_expression));
						used.add(_inter.receptor_symbol);
					}
				}
			}
			Collections.sort(results);
			Collections.reverse(results);
			interaction.cancer_ligand_receptor_ranking = new InteractionResult[results.size()];
			for (int i=0; i<results.size(); i++)
				interaction.cancer_ligand_receptor_ranking[i] = results.get(i);
		}
		
		/*
		 * KEGGHPRD_result_cancer_receptor
		 */		
		for (Interaction interaction : biodb.interactions) {
			// enumerate all receptor expressions
			double sum_of_ligand_expression_cancer = 0;
			double sum_of_ligand_expression_stroma = 0;
			for (Interaction _inter : biodb.interactions) {
				if (interaction.receptor_symbol.equals(_inter.receptor_symbol)) {
					if (_inter.ginput_ligand_cancer != null)
						sum_of_ligand_expression_cancer += _inter.ginput_ligand_cancer.normalizedExpression;
					if (_inter.ginput_ligand_stroma != null)
						sum_of_ligand_expression_stroma += _inter.ginput_ligand_stroma.normalizedExpression;
				}
			}
			
			// average
			if (interaction.ginput_receptor_cancer != null) { 
				interaction.cancer_receptor_average = Math.sqrt(
					(sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma)
					* interaction.ginput_receptor_cancer.normalizedExpression);
			}
			
			// receptor ratio
			if (sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma > 0)
				interaction.cancer_receptor_ligand_ratio = sum_of_ligand_expression_cancer / (sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma);
			else
				interaction.cancer_receptor_ligand_ratio = -1;
			
			// receptor ranking
			ArrayList<InteractionResult> results = new ArrayList<Analysis.InteractionResult>();
			HashSet<String> used = new HashSet<String>();
			for (Interaction _inter : biodb.interactions) {
				if (interaction.receptor_symbol.equals(_inter.receptor_symbol)) {
					double rec_expression = 0;
					if (_inter.ginput_ligand_cancer != null)
						rec_expression += _inter.ginput_ligand_cancer.normalizedExpression;
					if (_inter.ginput_ligand_stroma != null)
						rec_expression += _inter.ginput_ligand_stroma.normalizedExpression;
					if (!used.contains(_inter.ligand_symbol)) {
						results.add(new InteractionResult(_inter.ligand_symbol, rec_expression));
						used.add(_inter.ligand_symbol);
					}
				}
			}
			Collections.sort(results);
			Collections.reverse(results);
			interaction.cancer_receptor_ligand_ranking = new InteractionResult[results.size()];
			for (int i=0; i<results.size(); i++)
				interaction.cancer_receptor_ligand_ranking[i] = results.get(i);
		}
		
		/*
		 * KEGGHPRD_result_stroma_ligand
		 */		
		for (Interaction interaction : biodb.interactions) {
			// enumerate all receptor expressions
			double sum_of_receptor_expression_cancer = 0;
			double sum_of_receptor_expression_stroma = 0;
			for (Interaction _inter : biodb.interactions) {
				if (interaction.ligand_symbol.equals(_inter.ligand_symbol)) {
					if (_inter.ginput_receptor_cancer != null)
						sum_of_receptor_expression_cancer += _inter.ginput_receptor_cancer.normalizedExpression;
					if (_inter.ginput_receptor_stroma != null)
						sum_of_receptor_expression_stroma += _inter.ginput_receptor_stroma.normalizedExpression;
				}
			}
			
			// average
			if (interaction.ginput_ligand_stroma != null) { 
				interaction.stroma_ligand_average = Math.sqrt(
					(sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma)
					* interaction.ginput_ligand_stroma.normalizedExpression);
			}
			
			// receptor ratio
			if (sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma > 0)
				interaction.stroma_ligand_receptor_ratio = sum_of_receptor_expression_stroma / (sum_of_receptor_expression_cancer + sum_of_receptor_expression_stroma);
			else
				interaction.stroma_ligand_receptor_ratio = -1;
			
			// receptor ranking
			ArrayList<InteractionResult> results = new ArrayList<Analysis.InteractionResult>();
			HashSet<String> used = new HashSet<String>();
			for (Interaction _inter : biodb.interactions) {
				if (interaction.ligand_symbol.equals(_inter.ligand_symbol)) {
					double rec_expression = 0;
					if (_inter.ginput_receptor_cancer != null)
						rec_expression += _inter.ginput_receptor_cancer.normalizedExpression;
					if (_inter.ginput_receptor_stroma != null)
						rec_expression += _inter.ginput_receptor_stroma.normalizedExpression;
					if (!used.contains(_inter.receptor_symbol)) {
						results.add(new InteractionResult(_inter.receptor_symbol, rec_expression));
						used.add(_inter.receptor_symbol);
					}
				}
			}
			Collections.sort(results);
			Collections.reverse(results);
			interaction.stroma_ligand_receptor_ranking = new InteractionResult[results.size()];
			for (int i=0; i<results.size(); i++)
				interaction.stroma_ligand_receptor_ranking[i] = results.get(i);
		}

		/*
		 * KEGGHPRD_result_stroma_receptor
		 */		
		for (Interaction interaction : biodb.interactions) {
			// enumerate all receptor expressions
			double sum_of_ligand_expression_cancer = 0;
			double sum_of_ligand_expression_stroma = 0;
			for (Interaction _inter : biodb.interactions) {
				if (interaction.receptor_symbol.equals(_inter.receptor_symbol)) {
					if (_inter.ginput_ligand_cancer != null)
						sum_of_ligand_expression_cancer += _inter.ginput_ligand_cancer.normalizedExpression;
					if (_inter.ginput_ligand_stroma != null)
						sum_of_ligand_expression_stroma += _inter.ginput_ligand_stroma.normalizedExpression;
				}
			}
			
			// average
			if (interaction.ginput_receptor_stroma != null) { 
				interaction.stroma_receptor_average = Math.sqrt(
					(sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma)
					* interaction.ginput_receptor_stroma.normalizedExpression);
			}
			
			// receptor ratio
			if (sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma > 0)
				interaction.stroma_receptor_ligand_ratio = sum_of_ligand_expression_stroma / (sum_of_ligand_expression_cancer + sum_of_ligand_expression_stroma);
			else
				interaction.stroma_receptor_ligand_ratio = -1;
			
			// receptor ranking
			ArrayList<InteractionResult> results = new ArrayList<Analysis.InteractionResult>();
			HashSet<String> used = new HashSet<String>(); 
			for (Interaction _inter : biodb.interactions) {
				if (interaction.receptor_symbol.equals(_inter.receptor_symbol)) {
					double rec_expression = 0;
					if (_inter.ginput_ligand_cancer != null)
						rec_expression += _inter.ginput_ligand_cancer.normalizedExpression;
					if (_inter.ginput_ligand_stroma != null)
						rec_expression += _inter.ginput_ligand_stroma.normalizedExpression;
					if (!used.contains(_inter.ligand_symbol)) {
						results.add(new InteractionResult(_inter.ligand_symbol, rec_expression));
						used.add(_inter.ligand_symbol);
					}
				}
			}
			Collections.sort(results);
			Collections.reverse(results);
			interaction.stroma_receptor_ligand_ranking = new InteractionResult[results.size()];
			for (int i=0; i<results.size(); i++)
				interaction.stroma_receptor_ligand_ranking[i] = results.get(i);
		}
		
		Logger.logf("analyzing interaction done.");
	}

	public class InteractionResult implements Comparable<InteractionResult> {
		public String symbol;
		public double expression;

		public InteractionResult(String symbol, double expression) {
			this.symbol = symbol;
			this.expression = expression;
		}

		@Override
		public int compareTo(InteractionResult o) {
			double d = o.expression - this.expression;
			if (d > 0) return -1;
			else if (d < 0) return 1;
			else return 0;
		}
	}
}
