package genepi.imputationserver.util;

import java.util.List;
import java.util.Vector;

public class LigateTool {

	//returns a list of boolean. true on pos i --> chunk i needs swap.
	public static List<Boolean> ligate(List<String> headFiles,
			List<String> tailFiles) {

		List<Boolean> result = new Vector<Boolean>();

		for (int i = 0; i < headFiles.size(); i++) {
			result.add(false);
		}

		return result;
	}

}
