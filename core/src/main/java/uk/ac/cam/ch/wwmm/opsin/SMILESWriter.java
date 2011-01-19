package uk.ac.cam.ch.wwmm.opsin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import uk.ac.cam.ch.wwmm.opsin.Bond.SMILES_BOND_DIRECTION;
import uk.ac.cam.ch.wwmm.opsin.BondStereo.BondStereoValue;

class SMILESWriter {

	/**The organic atoms and their allowed implicit valences in SMILES */
	private static final Map<String,Integer[]> organicAtomsToStandardValencies = new HashMap<String, Integer[]>();

	/**Closures 1-9, %10-99, 0 */
	private static final  LinkedList<String> closureSymbols = new LinkedList<String>();


	/**The available ring closure symbols, ordered from start to end in the preferred order for use.*/
	private final LinkedList<String> availableClosureSymbols = new LinkedList<String>(closureSymbols);

	/**Maps between bonds and the ring closure to use when the atom that ends the bond is encountered.*/
	private final HashMap<Bond, String> bondToClosureSymbolMap = new HashMap<Bond, String>();

	/**Maps between bonds and the atom that this bond will go to in the SMILES */
	private final HashMap<Bond, Atom> bondToNextAtomMap= new HashMap<Bond, Atom>();

	/**The structure to be converted to SMILES*/
	private final Fragment structure;

	/**Holds the SMILES string which is under construction*/
	private final StringBuilder smilesBuilder = new StringBuilder();

	static {
		organicAtomsToStandardValencies.put("B", new Integer[]{3});
		organicAtomsToStandardValencies.put("C", new Integer[]{4});
		organicAtomsToStandardValencies.put("N", new Integer[]{3,5});//note that OPSIN doesn't accept valency 5 nitrogen without the lambda convention
		organicAtomsToStandardValencies.put("O", new Integer[]{2});
		organicAtomsToStandardValencies.put("P", new Integer[]{3,5});
		organicAtomsToStandardValencies.put("S", new Integer[]{2,4,6});
		organicAtomsToStandardValencies.put("F", new Integer[]{1});
		organicAtomsToStandardValencies.put("Cl", new Integer[]{1});
		organicAtomsToStandardValencies.put("Br", new Integer[]{1});
		organicAtomsToStandardValencies.put("I", new Integer[]{1});

		for (int i = 1; i <=9; i++) {
			closureSymbols.add(String.valueOf(i));
		}
		for (int i = 10; i <=99; i++) {
			closureSymbols.add("%"+i);
		}
		closureSymbols.add("0");
	}

	/**
	 * Creates a SMILES writer for the given fragment
	 * @param structure
	 */
	SMILESWriter(Fragment structure) {
		this.structure =structure;
	}

	/**
	 * Generates SMILES from the fragment the SMILESWriter was created with
	 * The following assumptions are currently made:
	 * 	The fragment contains no bonds to atoms outside the fragment
	 * 	Hydrogens are all explicit
	 * 	Spare valency has been converted to double bonds
	 * @return
	 */
	String generateSmiles() {
		List<Atom> atomList = structure.getAtomList();
		List<Atom> nonProtonAtomList = createNonProtonAtomList(atomList);
		int nonProtonCount = nonProtonAtomList.size();

		assignSmilesOrder();
		assignDoubleBondStereochemistrySlashes();
		boolean isEmpty =true;
		for (int i = 0; i < nonProtonCount; i++) {
			Atom currentAtom = nonProtonAtomList.get(i);
			if(currentAtom.getProperty(Atom.VISITED)==0){
				if (!isEmpty){
					smilesBuilder.append('.');
				}
				traverseSmiles(currentAtom, null, 0);
				isEmpty =false;
			}
		}

		return smilesBuilder.toString();
	}

	private List<Atom> createNonProtonAtomList(List<Atom> atomList) {
		List<Atom> nonProtonAtomList = new ArrayList<Atom>();
		for (Atom atom : atomList) {
			if (!isSmilesImplicitProton(atom)){
				nonProtonAtomList.add(atom);
			}
		}
		return nonProtonAtomList;
	}

	private boolean isSmilesImplicitProton(Atom atom) {
		if (!atom.getElement().equals("H") || (atom.getIsotope()!=null && atom.getIsotope()!=1) ){
			return false;
		}
		else{
			//special case where hydrogen is a counter ion or only connects to other hydrogen
			List<Atom> neighbours = atom.getAtomNeighbours();
			boolean foundNonHydrogenNeighbour =false;
			for (Atom neighbour : neighbours) {
				if (!neighbour.getElement().equals("H")){
					foundNonHydrogenNeighbour =true;
				}
			}
			if (!foundNonHydrogenNeighbour || neighbours.size()>1){
				return false;
			}
		}
		return true;
	}

	private void assignSmilesOrder() {
		List<Atom> atomList =structure.getAtomList();
		for (Atom atom : atomList) {
			atom.setProperty(Atom.VISITED, null);
		}
		for (Atom a : atomList) {
			if(a.getProperty(Atom.VISITED)==null && !isSmilesImplicitProton(a)){//typically for all but the first atom this will be true
				traverseRings(a, null, 0);
			}
		}
	}

	private int traverseRings(Atom currentAtom, Atom previousAtom, int depth){
		int temp, result;
		if(currentAtom.getProperty(Atom.VISITED)!=null){
			return currentAtom.getProperty(Atom.VISITED);
		}
		currentAtom.setProperty(Atom.VISITED, depth);
		result = depth+1;
		Set<Bond> bonds = currentAtom.getBonds();
		for (Bond bond : bonds) {
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (isSmilesImplicitProton(neighbour)){
				continue;
			}
			if (neighbour.equals(previousAtom)){
				continue;
			}
			bondToNextAtomMap.put(bond, neighbour);
			temp = traverseRings(neighbour, currentAtom, depth+1);
			if( temp <= depth) {
				result = Math.min(result,temp);
			}
		}
		if( result <= depth ){
			currentAtom.setAtomIsInACycle(true);
		}
		return result;
	
	}

	/**
	 * Goes through the bonds that do not go to implicit protons identifying those with bond stereo.
	 * For these cases the bondStereo is used in combination with the directionality of the bonds to determine whether
	 * two of the adjacent bonds should be represented by / or \ in the SMILES
	 */
	private void assignDoubleBondStereochemistrySlashes() {
		Set<Bond> bonds = bondToNextAtomMap.keySet();
		for (Bond bond : bonds) {
			BondStereo bondStereo =bond.getBondStereo();
			if (bondStereo!=null){
				Atom[] atomRefs4 = bondStereo.getAtomRefs4();
				Bond bond1 = atomRefs4[0].getFrag().findBond(atomRefs4[0], atomRefs4[1]);
				Bond bond2 = atomRefs4[0].getFrag().findBond(atomRefs4[2], atomRefs4[3]);
				Atom bond1ToAtom = bondToNextAtomMap.get(bond1);
				Atom bond2ToAtom = bondToNextAtomMap.get(bond2);
				if (bondStereo.getBondStereoValue().equals(BondStereoValue.CIS)){
					if (bond1ToAtom.equals(atomRefs4[1])){
						bond1.setSmilesStereochemistry(SMILES_BOND_DIRECTION.LSLASH);
					}
					else{
						bond1.setSmilesStereochemistry(SMILES_BOND_DIRECTION.RSLASH);
					}
	
					if (bond2ToAtom.equals(atomRefs4[3])){
						bond2.setSmilesStereochemistry(SMILES_BOND_DIRECTION.RSLASH);
					}
					else{
						bond2.setSmilesStereochemistry(SMILES_BOND_DIRECTION.LSLASH);
					}
				}
				else{
					if (bond1ToAtom.equals(atomRefs4[1])){
						bond1.setSmilesStereochemistry(SMILES_BOND_DIRECTION.LSLASH);
					}
					else{
						bond1.setSmilesStereochemistry(SMILES_BOND_DIRECTION.RSLASH);
					}
	
					if (bond2ToAtom.equals(atomRefs4[3])){
						bond2.setSmilesStereochemistry(SMILES_BOND_DIRECTION.LSLASH);
					}
					else{
						bond2.setSmilesStereochemistry(SMILES_BOND_DIRECTION.RSLASH);
					}
				}
			}
		}
	}

	private void traverseSmiles(Atom currentAtom, Atom previousAtom, int depth){
		smilesBuilder.append(atomToSmiles(currentAtom, depth, previousAtom));
		Set<Bond> bonds = currentAtom.getBonds();
		for (Bond bond : bonds) {
			Atom neighbour = bond.getOtherAtom(currentAtom);
			Integer nDepth = neighbour.getProperty(Atom.VISITED);
			if ( nDepth!=null && nDepth<=depth && !neighbour.equals(previousAtom)){
				String closure = bondToClosureSymbolMap.get(bond);
				availableClosureSymbols.addFirst(closure);
				smilesBuilder.append(closure);
			}
		}
		for (Bond bond : bonds) {
			Atom neighbour = bond.getOtherAtom(currentAtom);
			Integer nDepth = neighbour.getProperty(Atom.VISITED);
			if (nDepth!=null && nDepth> (depth +1)){
				String closure = availableClosureSymbols.removeFirst();
				bondToClosureSymbolMap.put(bond, closure);
				smilesBuilder.append(bondToSmiles(bond));
				smilesBuilder.append(closure);
			}
	
		}
		// count outgoing edges
		int count = 0;
		for (Bond bond : bonds) {
			Atom neighbour = bond.getOtherAtom(currentAtom);
			Integer nDepth = neighbour.getProperty(Atom.VISITED);
			if (nDepth!=null && nDepth==depth+1){
				count++;
			}
		}
	
		for (Bond bond : bonds) {
			Atom neighbour = bond.getOtherAtom(currentAtom);
			Integer nDepth = neighbour.getProperty(Atom.VISITED);
			if (nDepth!=null && nDepth==depth+1){
				if (count > 1){
				  smilesBuilder.append('(');
				}
				smilesBuilder.append(bondToSmiles(bond));
				traverseSmiles(neighbour,currentAtom,depth+1);
				if (count > 1){
					smilesBuilder.append(')');
					count--;
				}
			}
		}
	}

	private String atomToSmiles(Atom atom, int depth, Atom previousAtom) {
		StringBuilder atomSmiles = new StringBuilder();
		int hydrogen =calculateNumberOfBondedExplicitHydrogen(atom);
		boolean needsSquareBrackets = determineWhetherAtomNeedsSquareBrackets(atom, hydrogen);
		if (needsSquareBrackets){
			atomSmiles.append('[');
		}
		if (atom.getIsotope()!=null){
			atomSmiles.append(atom.getIsotope());
		}
		if (atom.hasSpareValency()){//spare valency corresponds directly to lower case SMILES in OPSIN's SMILES reader
			atomSmiles.append(atom.getElement().toLowerCase());
		}
		else{
			atomSmiles.append(atom.getElement());
		}
		if (atom.getAtomParity()!=null){
			atomSmiles.append(atomParityToSmiles(atom, hydrogen, depth, previousAtom));
		}
		else{
			if (hydrogen !=0 && needsSquareBrackets && !atom.getElement().equals("H")){
				atomSmiles.append('H');
				if (hydrogen !=1){
					atomSmiles.append(String.valueOf(hydrogen));
				}
			}
		}
		int charge = atom.getCharge();
	    if (charge != 0){
	    	if (charge==1){
	    		atomSmiles.append('+');
	    	}
	    	else if (charge==-1){
	    		atomSmiles.append('-');
	    	}
	    	else{
	    		if (charge>0){
	    			atomSmiles.append('+');
	    		}
	    		atomSmiles.append(charge);
	    	}
	    }
	    //atomSmiles.append(":" + atomToSmilesOrderMap.get(atom));
	    if (needsSquareBrackets){
	    	atomSmiles.append(']');
	    }
		return atomSmiles.toString();
	}

	private int calculateNumberOfBondedExplicitHydrogen(Atom atom) {
		List<Atom> neighbours = atom.getAtomNeighbours();
		int count =0;
		for (Atom neighbour : neighbours) {
			if (neighbour.getProperty(Atom.VISITED)==null){
				count++;
			}
		}
		return count;
	}

	private boolean determineWhetherAtomNeedsSquareBrackets(Atom atom, int hydrogenCount) {
		if (!organicAtomsToStandardValencies.containsKey(atom.getElement())){
			return true;
		}
		if (atom.getCharge()!=0){
			return true;
		}
		if (atom.getIsotope()!=null){
			return true;
		}
		if (atom.getAtomParity()!=null){
			return true;
		}
	
		List<Integer> expectedValencies = Arrays.asList(organicAtomsToStandardValencies.get(atom.getElement()));
		int valency = atom.getIncomingValency();
		boolean valencyCanBeDescribedImplicitly = expectedValencies.contains(valency);
		int targetImplicitValency =valency;
		if (valency > expectedValencies.get(expectedValencies.size()-1)){
			valencyCanBeDescribedImplicitly =true;
		}
		if (!valencyCanBeDescribedImplicitly){
			return true;
		}
	
		int nonHydrogenValency = valency -hydrogenCount;
		int implicitValencyThatWouldBeGenerated = nonHydrogenValency;
		for (int i = expectedValencies.size()-1; i>=0; i--) {
			if (expectedValencies.get(i) >= nonHydrogenValency){
				implicitValencyThatWouldBeGenerated =expectedValencies.get(i);
			}
		}
		if (targetImplicitValency != implicitValencyThatWouldBeGenerated){
			return true;
		}
		return false;
	}

	private String atomParityToSmiles(Atom currentAtom, int hydrogen, int depth, Atom previousAtom) {
		AtomParity atomParity = currentAtom.getAtomParity();
		StringBuilder tetrahedralStereoChem = new StringBuilder();
		Atom[] atomRefs4 = atomParity.getAtomRefs4().clone();

		List<Atom> atomrefs4Current = new ArrayList<Atom>();
		//TODO support sulfones

		Set<Bond> bonds = currentAtom.getBonds();
		for (Bond bond : bonds) {//previous atom
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (!isSmilesImplicitProton(neighbour) && neighbour.equals(previousAtom) ){
				atomrefs4Current.add(neighbour);
			}
		}
		for (Bond bond : bonds) {//implicit hydrogen
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (isSmilesImplicitProton(neighbour)){
				atomrefs4Current.add(currentAtom);
			}
		}
		for (Bond bond : bonds) {//ring closures
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (isSmilesImplicitProton(neighbour)){
				continue;
			}
			if (neighbour.getProperty(Atom.VISITED)<=depth && !neighbour.equals(previousAtom) ){
				atomrefs4Current.add(neighbour);
			}
		}
		for (Bond bond : bonds) {//ring openings
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (isSmilesImplicitProton(neighbour)){
				continue;
			}

			if (neighbour.getProperty(Atom.VISITED)> (depth +1)){
				atomrefs4Current.add(neighbour);
			}

		}
		for (Bond bond : bonds) {//next atom/s
			Atom neighbour = bond.getOtherAtom(currentAtom);
			if (isSmilesImplicitProton(neighbour)){
				continue;
			}
			if (neighbour.getProperty(Atom.VISITED)==depth+1){
				atomrefs4Current.add(neighbour);
			}
		}
		Atom[] atomrefs4CurrentArr = new Atom[4];
		for (int i = 0; i < atomrefs4Current.size(); i++) {
			atomrefs4CurrentArr[i] = atomrefs4Current.get(i);
		}
		for (int i = 0; i < atomRefs4.length; i++) {
			if (isSmilesImplicitProton(atomRefs4[i])){
				atomRefs4[i] = currentAtom;
			}
		}

		boolean equivalent = StereochemistryHandler.checkEquivalencyOfAtomsRefs4AndParity(atomRefs4, atomParity.getParity(), atomrefs4CurrentArr, 1);
		if (equivalent){
			tetrahedralStereoChem.append("@@");
		}
		else{
			tetrahedralStereoChem.append("@");
		}
		if (hydrogen==1){
			tetrahedralStereoChem.append('H');
		}
		return tetrahedralStereoChem.toString();
	}

	private String bondToSmiles(Bond bond){
		String bondSmiles ="";
		if (bond.getOrder()==2){
			bondSmiles ="=";
		}
		else if (bond.getOrder()==3){
			bondSmiles ="#";
		}
		else if (bond.getSmilesStereochemistry()!=null){
			if (bond.getSmilesStereochemistry()==SMILES_BOND_DIRECTION.RSLASH){
				bondSmiles ="/";
			}
			else{
				bondSmiles ="\\";
			}
		}
		return bondSmiles;
	}
}
