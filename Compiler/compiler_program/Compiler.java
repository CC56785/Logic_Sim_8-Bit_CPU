package compiler_program;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import compiler_program.Compiler.Command.PseudoCommand;

public class Compiler {
	private static final boolean DEBUG_CLEANED_ASSEMBLY = true;

	private static final ByteArrayOutputStream errorBuffer = new ByteArrayOutputStream();
	private static final PrintStream errorBufferStream = new PrintStream(errorBuffer);

	public static void startCompiling(String[] args) {
		if (args.length == 0) {
			System.out.println("Please pass the File to compile as Argument!");
			return;
		}

		if (!args[0].endsWith(".txt")) {
			System.out.println("Can only read .txt Files!");
			return;
		}
		String name = args[0].substring(0, args[0].length() - 4);
		String resultName = "out/" + name + "_out.txt";

		List<String> rawLines;
		try {
			rawLines = Files.readAllLines(Paths.get(args[0]));
		} catch (IOException e) {
			System.out.println("An Error occurred while reading the File!");
			return;
		}

		Compiler compiler = new Compiler();
		List<Line> lines = compiler.getLinesFromStrings(rawLines);

		int numProblems = 0;
		numProblems += compiler.compile(lines);
		String result = compiler.convertToString(lines);

		try {
			Files.write(Paths.get(resultName), result.getBytes(), StandardOpenOption.CREATE);
		} catch (IOException e) {
			System.out.println("An Error occurred while writing the compiled Program!");
			return;
		}

		if (numProblems == 0) {
			System.out.println(name + " was successfully compiled and saved in \"" + resultName + "\".");
		} else {
			System.out.println(errorBuffer);
			System.out.println(name + " was compiled with " + numProblems + " Problems and saved in \"" + resultName + "\".");
		}
	}

	private int compile(List<Line> lines) {
		int numProblems = 0;
		prepareLines(lines);

		HashMap<String, String> defines = new HashMap<>();
		numProblems += getDefines(lines, defines);
		replaceAllTextBasedOnMap(lines, defines);

		HashMap<String, String> labels = new HashMap<>();
		numProblems += getLabels(lines, labels);
		replaceAllTextBasedOnMap(lines, labels);

		numProblems += setUpCommands(lines);
		setFinalLineNumbers(lines);

		numProblems += generateAllMachineCodeParts(lines);
		generateMachineCodes(lines);

		return numProblems;
	}

	private void prepareLines(List<Line> lines) {
		for (Line line : lines) {
			line.cleanLine();
			if (line.isValid) {
				line.prepareParts();
			}
		}

		if (DEBUG_CLEANED_ASSEMBLY) {
			System.out.println("Cleaned Assembly:");
			for (Line line : lines) {
				if (line.isValid) {
					System.out.println(line);
				}
			}
			System.out.println();
		}
	}

	/**
	 * IMPORTANT: defines currently don't support any text containing a comma or a space (they will just be ignored / cause an error).
	 * Also, it doesn't matter where a #define Tag is placed, it will always be applied to the entire document!
	 */
	private int getDefines(List<Line> lines, HashMap<String, String> defines) {
		int numProblems = 0;
		for (Line line : lines) {
			if (!line.isValid) continue;

			if (line.parts.get(0).equals("#define")) {
				if (line.parts.size() == 3) {
					defines.put(line.parts.get(1), line.parts.get(2));
				} else {
					errorBufferStream.println(line.getErrorString() + "Could not resolve the define!");
					numProblems++;
				}
				line.isValid = false;
			}
		}
		return numProblems;
	}

	/**
	 * IMPORTANT: It doesn't matter where a #label Tag is placed, it will always be accessible from the entire document!
	 */
	private int getLabels(List<Line> lines, HashMap<String, String> labels) {
		int numProblems = 0;
		for (Line line : lines) {
			if (!line.isValid) continue;

			if (line.parts.get(0).equals("#label")) {
				if (line.parts.size() == 2) {
					labels.put(line.parts.get(1), String.valueOf(line.lineNumber));
				} else {
					errorBufferStream.println(line.getErrorString() + "Could not resolve the label!");
					numProblems++;
				}
				line.isValid = false;
			}
		}
		return numProblems;
	}

	private void replaceAllTextBasedOnMap(List<Line> lines, HashMap<String, String> replacementMap) {
		for (String toBeReplaced : replacementMap.keySet()) {
			for (Line line : lines) {
				if (!line.isValid) continue;
				for (int i = 0; i < line.parts.size(); i++) {
					line.parts.set(i, line.parts.get(i).replaceAll(toBeReplaced, replacementMap.get(toBeReplaced)));
				}
			}
		}
	}

	private int setUpCommands(List<Line> lines) {
		int numProblems = 0;
		for (Line line : lines) {
			if (!line.isValid) continue;
			numProblems += line.setUpCommand();
		}
		return numProblems;
	}

	private void setFinalLineNumbers(List<Line> lines) {
		int lineNumber = 0;
		for (Line line : lines) {
			if (!line.isValid) continue;
			line.machineCodeLineNumber = lineNumber;
			lineNumber++;
		}
	}

	private int generateAllMachineCodeParts(List<Line> lines) {
		int numProblems = 0;
		for (Line line : lines) {
			if (!line.isValid) continue;
			numProblems += line.generateMachineCodeParts();
		}

		if (DEBUG_CLEANED_ASSEMBLY) {
			System.out.println("Fully Resolved Assembly:");
			for (Line line : lines) {
				if (line.isValid) {
					System.out.println(line);
				}
			}
			System.out.println();
		}

		return numProblems;
	}

	private void generateMachineCodes(List<Line> lines) {
		for (Line line : lines) {
			if (!line.isValid) continue;
			line.generateMachineCode();
		}
	}

	private List<Line> getLinesFromStrings(List<String> stringLines) {
		List<Line> lines = new ArrayList<>();
		int lineNumber = 1;
		for (String stringLine : stringLines) {
			lines.add(new Line(stringLine, lines, lineNumber));
			lineNumber++;
		}
		return lines;
	}

	private String convertToString(List<Line> lines) {
		StringBuilder result = new StringBuilder();
		int counter = 0;
		for (Line line : lines) {
			if (counter >= 256) {
				errorBufferStream.println("ERROR: Program is too long to fit in 256 Instructions!");
				errorBufferStream.println();
				break;
			}

			if (!line.isValid) continue;
			String binary = String.format("%16s", Integer.toBinaryString(line.machineCode & 0xFFFF)).replace(' ', '0');
			result.append(binary);
			result.append(System.lineSeparator());
			counter++;
		}
		while (counter < 256) {
			result.append("0000000000000000");
			result.append(System.lineSeparator());
			counter++;
		}
		return result.toString();
	}

	private class Line {
		private final List<Line> allLines;
		/**
		 * starting at index 1
		 */
		private final int lineNumber;

		private String fullString;
		private List<String> parts;
		private Command command;
		private List<String> args;
		private int machineCodeLineNumber;
		private byte[] machineCodeParts;
		private short machineCode;
		private boolean isValid;

		private Line(String string, List<Line> allLines, int lineNumber) {
			this.allLines = allLines;
			this.lineNumber = lineNumber;
			fullString = string;
			parts = null;
			isValid = true;
		}

		private void cleanLine() {
			fullString = fullString.toLowerCase();
			fullString = fullString.replace(',', ' ');
			if (fullString.contains("//")) {
				fullString = fullString.substring(0, fullString.indexOf("//"));
			}
			fullString = fullString.trim();
			if (fullString.isEmpty()) {
				isValid = false;
			}
		}

		private void prepareParts() {
			parts = new ArrayList<>(List.of(fullString.split(" ")));
			for (int i = 0; i < parts.size(); i++) {
				parts.set(i, parts.get(i).trim());
			}
			parts.removeIf(s -> s.isEmpty());
		}

		/**
		 * @return number of Problems
		 */
		private int setUpCommand() {
			if (PseudoCommand.hasPseudoCommandOfString(parts.get(0))) {
				PseudoCommand pseudoCommand = PseudoCommand.getPseudoCommandFromString(parts.get(0));
				int oldNumberOfArgs = parts.size() - 1;
				parts = pseudoCommand.convert(parts);
				if (parts == null) {
					errorBufferStream.println(getErrorString() + "This Pseudo-Command expected "
					+ pseudoCommand.numberOfArgs + " Arguments but found " + oldNumberOfArgs + "!");
					isValid = false;
					return 1;
				}
			}
			if (!Command.hasCommandOfString(parts.get(0))) {
				errorBufferStream.println(getErrorString() + "No Matching Command found!");
				isValid = false;
				return 1;
			}
			command = Command.getCommandFromString(parts.get(0));
			args = new LinkedList<>(parts);
			args.remove(0);
			return 0;
		}

		/**
		 * @return number of Problems
		 */
		private int generateMachineCodeParts() {
			return command.setMachineCodePartsInLine(this);
		}

		private void generateMachineCode() {
			if (machineCodeParts == null) {
				machineCode = 0;
				return;
			}
			machineCode = (short) (machineCodeParts[0] & 0x000f);
			machineCode <<= 4;
			machineCode |= (short) (machineCodeParts[1] & 0x000f);
			machineCode <<= 4;
			machineCode |= (short) (machineCodeParts[2] & 0x000f);
			machineCode <<= 4;
			machineCode |= (short) (machineCodeParts[3] & 0x000f);
		}

		private String getErrorString() {
			return "ERROR in Line " + lineNumber + ": ";
		}

		@Override
		public String toString() {
			if (parts == null) {
				return "raw: " + fullString;
			} else {
				StringBuilder res = new StringBuilder();
				for (String part : parts) {
					res.append(part);
					res.append(" ");
				}
				return res.toString();
			}
		}
	}

	public static abstract class Command {
		private static final HashMap<String, Supplier<Command>> COMMAND_NAMES = initializeCommandNames();

		private byte registerNumberBuffer;

		protected abstract int getNumberOfArgs();

		protected abstract byte getOpCode();

		protected byte getFunc4OpCode() {
			return -1;
		}

		protected int setMachineCodePartsInLine(Line line) {
			if (line.args.size() != getNumberOfArgs()) {
				errorBufferStream.println(line.getErrorString() + "Expected " + getNumberOfArgs() + " Arguments but found " + line.args.size() + "!");
				return 1;
			}

			line.machineCodeParts = new byte[4];
			line.machineCodeParts[0] = getOpCode();
			if (getFunc4OpCode() != -1) {
				line.machineCodeParts[3] = getFunc4OpCode();
			}
			return 0;
		}

		private int setRegisterNumberBufferFromString(Line line, String register) {
			if (register.length() != 2 || register.charAt(0) != 'r') {
				errorBufferStream.println(line.getErrorString() + "Invalid Register!");
				return 1;
			}
			char charNumber = register.charAt(1);
			byte number = (byte) (charNumber - '0');
			if (number < 0 || number > 7) {
				errorBufferStream.println(line.getErrorString() + "Invalid Register Number!");
				return 1;
			}
			registerNumberBuffer = number;
			return 0;
		}

		protected int setMachineCodePartToRegNumber(Line line, int machineCodePartIndex, String registerString) {
			int numProblems = setRegisterNumberBufferFromString(line, registerString);
			if (numProblems > 0) registerNumberBuffer = 0;
			line.machineCodeParts[machineCodePartIndex] = registerNumberBuffer;
			return numProblems;
		}

		protected int setMachineCodePartsToImmediate(Line line, int indexInArgsToImmediate) {
			int numProblems = 0;
			String numberAsString = line.args.get(indexInArgsToImmediate);
			int number = 0;
			try {
				number = decodeNumber(numberAsString);
			} catch (NumberFormatException e) {
				errorBufferStream.println(line.getErrorString() + "Could not parse Immediate!");
				numProblems++;
			}
			if (number < -128 || number > 127) {
				errorBufferStream.println(line.getErrorString() + "Immediate " + number + " is not in valid range!");
				numProblems++;
			}
			setMachineCodePartsToImmediate(line, (byte) number);
			return numProblems;
		}

		protected void setMachineCodePartsToImmediate(Line line, byte immediate) {
			line.machineCodeParts[2] = (byte) ((immediate & 0xf0) >> 4);
			line.machineCodeParts[3] = (byte) (immediate & 0x0f);
		}

		private int decodeNumber(String s) {
			if (s.startsWith("0x")) {
				return (byte) Integer.decode(s).intValue();
			}
			return Integer.valueOf(s);
		}

		private static boolean hasCommandOfString(String commandName) {
			return COMMAND_NAMES.containsKey(commandName);
		}

		private static Command getCommandFromString(String command) {
			return COMMAND_NAMES.get(command).get();
		}

		private static HashMap<String, Supplier<Command>> initializeCommandNames() {
			HashMap<String, Supplier<Command>> names = new HashMap<>();
			names.put("add", AddCommand::new);
			names.put("sub", SubCommand::new);
			names.put("and", AndCommand::new);
			names.put("or", OrCommand::new);
			names.put("xor", XorCommand::new);
			names.put("addi", AddImmediateCommand::new);
			names.put("andi", AndImmediateCommand::new);
			names.put("ori", OrImmediateCommand::new);
			names.put("xori", XorImmediateCommand::new);
			names.put("li", LoadImmediateCommand::new);
			names.put("la", LoadAddressCommand::new);
			names.put("beq", BranchEqualsCommand::new);
			names.put("bgt", BranchGreaterThanCommand::new);
			names.put("bge", BranchGreaterEqualsCommand::new);
			names.put("lb", LoadByteCommand::new);
			names.put("sb", StoreByteCommand::new);
			return names;
		}

		public enum PseudoCommand {
			NO_OPERATION("nop", 0, l -> List.of("add", "r0", "r0", "r0")),
			BRANCH_LESS_THAN("blt", 3, l -> List.of("bgt", l.get(1), l.get(3), l.get(2))),
			BRANCH_LESS_EQUALS("ble", 3, l -> List.of("bge", l.get(1), l.get(3), l.get(2))),
			JUMP_UNCONDITIONAL("j", 1, l -> List.of("beq", l.get(1), "r0", "r0")),
			SHIFT_LEFT_LOGICAL("sll", 2, l -> List.of("add", l.get(1), l.get(2), l.get(2))),
			BRANCH_LESS_EQUALS_ZERO("blez", 2, l -> List.of("bge", l.get(1), "r0", l.get(2))),
			BRANCH_LESS_THAN_ZERO("bltz", 2, l -> List.of("bgt", l.get(1), "r0", l.get(2))),
			BRANCH_GREATER_EQUALS_ZERO("bgez", 2, l -> List.of("bge", l.get(1), l.get(2), "r0")),
			BRANCH_GREATER_THAN_ZERO("bgtz", 2, l -> List.of("bgt", l.get(1), l.get(2), "r0")),
			NEGATE("neg", 2, l -> List.of("sub", l.get(1), "r0",  l.get(2)));

			private final Function<List<String>, List<String>> converter;
			private final String name;
			private final int numberOfArgs;

			private PseudoCommand(String name, int numberOfArgs, Function<List<String>, List<String>> converter) {
				this.converter = converter;
				this.name = name;
				this.numberOfArgs = numberOfArgs;
			}

			public List<String> convert(List<String> parts) {
				if (parts.size() - 1 != numberOfArgs) {
					return null;
				}
				return converter.apply(parts);
			}

			private static boolean hasPseudoCommandOfString(String s) {
				for (PseudoCommand pseudoCommand : values()) {
					if (pseudoCommand.name.equals(s)) {
						return true;
					}
				}
				return false;
			}

			private static PseudoCommand getPseudoCommandFromString(String s) {
				for (PseudoCommand pseudoCommand : values()) {
					if (pseudoCommand.name.equals(s)) {
						return pseudoCommand;
					}
				}
				return null;
			}
		}
	}

	/**
	 * Command of format: com r1, r2, r3
	 * and functionality r1 := r2 § r3, where § is some operation
	 */
	public abstract static class CalculationCommand extends Command {
		@Override
		protected int getNumberOfArgs() {
			return 3;
		}

		@Override
		protected int setMachineCodePartsInLine(Line line) {
			int numProblems = super.setMachineCodePartsInLine(line);
			if (numProblems > 0) return numProblems;

			numProblems += setMachineCodePartToRegNumber(line, 1, line.args.get(0));
			numProblems += setMachineCodePartToRegNumber(line, 2, line.args.get(1));
			numProblems += setMachineCodePartToRegNumber(line, 3, line.args.get(2));

			return numProblems;
		}
	}

	/**
	 * Command of format: com r1, x
	 * and functionality r1 := r1 § x, where § is some operation, and x is a number
	 */
	public abstract static class ImmediateCommand extends Command {
		@Override
		protected int getNumberOfArgs() {
			return 2;
		}

		@Override
		protected int setMachineCodePartsInLine(Line line) {
			int numProblems = super.setMachineCodePartsInLine(line);
			if (numProblems > 0) return numProblems;

			numProblems += setMachineCodePartToRegNumber(line, 1, line.args.get(0));
			numProblems += setMachineCodePartsToImmediate(line, 1);

			return numProblems;
		}
	}

	/**
	 * Command of format: com r1, (r2)
	 * where r2 contains a memory address, and r1 is either a source or a destination register
	 */
	public abstract static class MemoryCommand extends Command {
		@Override
		protected int getNumberOfArgs() {
			return 2;
		}

		@Override
		protected byte getOpCode() {
			return 0b0101;
		}

		@Override
		protected int setMachineCodePartsInLine(Line line) {
			int numProblems = super.setMachineCodePartsInLine(line);
			if (numProblems > 0) return numProblems;

			numProblems += setMachineCodePartToRegNumber(line, 1, line.args.get(0));

			String memAddressString = line.args.get(1);
			if (memAddressString.length() != 4 || memAddressString.charAt(0) != '(' || memAddressString.charAt(3) != ')') {
				errorBufferStream.println(line.getErrorString() + "Invalid Memory Address Format! (should be for example \"(r3)\")");
				numProblems++;
				memAddressString = "r0";
			} else {
				memAddressString = memAddressString.substring(1, 3);
			}
			numProblems += setMachineCodePartToRegNumber(line, 2, memAddressString);

			return numProblems;
		}
	}

	public static class AddCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b0000;
		}
	}

	public static class SubCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b0001;
		}
	}

	public static class AndCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b0111;
		}
	}

	public static class OrCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b0110;
		}
	}

	public static class XorCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b0100;
		}
	}

	public static class AddImmediateCommand extends ImmediateCommand {
		@Override
		protected byte getOpCode() {
			return 0b1000;
		}
	}

	public static class AndImmediateCommand extends ImmediateCommand {
		@Override
		protected byte getOpCode() {
			return 0b1111;
		}
	}

	public static class OrImmediateCommand extends ImmediateCommand {
		@Override
		protected byte getOpCode() {
			return 0b1110;
		}
	}

	public static class XorImmediateCommand extends ImmediateCommand {
		@Override
		protected byte getOpCode() {
			return 0b1100;
		}
	}

	public static class LoadImmediateCommand extends ImmediateCommand {
		@Override
		protected byte getOpCode() {
			return 0b1010;
		}
	}

	public static class LoadAddressCommand extends Command {
		private static byte actualLineNumberBuffer;

		@Override
		protected int getNumberOfArgs() {
			return 2;
		}

		@Override
		protected byte getOpCode() {
			return 0b1010;
		}

		@Override
		protected int setMachineCodePartsInLine(Line line) {
			int numProblems = super.setMachineCodePartsInLine(line);
			if (numProblems > 0) return numProblems;

			numProblems += setMachineCodePartToRegNumber(line, 1, line.args.get(0));
			numProblems += setActualLineNumberBuffer(line);
			setMachineCodePartsToImmediate(line, actualLineNumberBuffer);

			// for printing the correct resolved code
			line.parts.set(2, String.valueOf(actualLineNumberBuffer));

			return numProblems;
		}

		private int setActualLineNumberBuffer(Line line) {
			int numProblems = 0;
			String lineAsString = line.args.get(1);
			int lineNumber = 1;
			try {
				lineNumber = Integer.valueOf(lineAsString);
			} catch (NumberFormatException e) {
				errorBufferStream.println(line.getErrorString() + "Could not parse the Line Number!");
				numProblems++;
			}

			if (lineNumber <= 0) {
				errorBufferStream.println(line.getErrorString() + "A Line Number to jump to must be greater than 0!");
				numProblems++;
				lineNumber = 1;
			}

			int validLineNumber = getNextValidLine(line.allLines, lineNumber);
			if (validLineNumber == -1) {
				errorBufferStream.println(line.getErrorString() + "No valid Line left after the given Line Number to jump to!");
				numProblems++;
				validLineNumber = 1;
			}

			int actualLineNumber = line.allLines.get(validLineNumber - 1).machineCodeLineNumber;
			if (actualLineNumber < 0 || actualLineNumber >= 256) {
				errorBufferStream.println(line.getErrorString() + "Line Number " + actualLineNumber
				+ " is not in valid range! (This is the final translated Line Number.)");
				numProblems++;
			}

			if (actualLineNumber > 127) {
				actualLineNumber -= 256;
			}

			actualLineNumberBuffer = (byte) actualLineNumber;
			return numProblems;
		}

		private int getNextValidLine(List<Line> lines, int currentLine) {
			while (currentLine - 1 < lines.size()) {
				if (lines.get(currentLine - 1).isValid) {
					return currentLine;
				}
				currentLine++;
			}
			return -1;
		}
	}

	public static class BranchEqualsCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b1011;
		}
	}

	public static class BranchGreaterThanCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b1101;
		}
	}

	public static class BranchGreaterEqualsCommand extends CalculationCommand {
		@Override
		protected byte getOpCode() {
			return 0b1001;
		}
	}

	public static class LoadByteCommand extends MemoryCommand {
		@Override
		protected byte getFunc4OpCode() {
			return 0b0000;
		}
	}

	public static class StoreByteCommand extends MemoryCommand {
		@Override
		protected byte getFunc4OpCode() {
			return 0b0001;
		}
	}
}
