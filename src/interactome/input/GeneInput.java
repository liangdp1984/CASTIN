package interactome.input;

import interactome.data.Gene;
import interactome.data.Refseq;

public class GeneInput {
	public Gene gene;
	public Refseq representativeRefseq;
	public RefseqInput representativeRefseqInput;
	public double normalizedExpression;
	
	public GeneInput(Gene gene) {
		this.gene = gene;
		this.representativeRefseq = null;
	}
}
