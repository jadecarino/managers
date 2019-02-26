package io.ejat.zos3270.internal.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.validation.constraints.NotNull;

import org.apache.commons.codec.binary.Hex;

import io.ejat.zos3270.FieldNotFoundException;
import io.ejat.zos3270.KeyboardLockedException;
import io.ejat.zos3270.TextNotFoundException;
import io.ejat.zos3270.TimeoutException;
import io.ejat.zos3270.internal.comms.Inbound3270Message;
import io.ejat.zos3270.internal.datastream.AttentionIdentification;
import io.ejat.zos3270.internal.datastream.BufferAddress;
import io.ejat.zos3270.internal.datastream.CommandCode;
import io.ejat.zos3270.internal.datastream.CommandEraseWrite;
import io.ejat.zos3270.internal.datastream.Order;
import io.ejat.zos3270.internal.datastream.OrderInsertCursor;
import io.ejat.zos3270.internal.datastream.OrderRepeatToAddress;
import io.ejat.zos3270.internal.datastream.OrderSetBufferAddress;
import io.ejat.zos3270.internal.datastream.OrderStartField;
import io.ejat.zos3270.internal.datastream.OrderText;
import io.ejat.zos3270.internal.datastream.WriteControlCharacter;
import io.ejat.zos3270.internal.terminal.fields.Field;
import io.ejat.zos3270.internal.terminal.fields.FieldChars;
import io.ejat.zos3270.internal.terminal.fields.FieldStartOfField;
import io.ejat.zos3270.internal.terminal.fields.FieldText;
import io.ejat.zos3270.spi.DatastreamException;

/**
 * Screen representation of the 3270 terminal
 * 
 * @author Michael Baylis
 *
 */
public class Screen {

	private LinkedList<Field> fields = new LinkedList<>();

	private final int columns;
	private final int rows;
	private final int screenSize;

	private int workingCursor = 0;

	private int screenCursor = 0;

	private Semaphore keyboardLock = new Semaphore(1);
	private boolean keyboardLockSet = false;

	/**
	 * Create a default screen
	 */
	public Screen() {
		this(80, 24);
	}

	/**
	 * Create a default screen
	 * 
	 * @param columns - Number of columns
	 * @param rows - Number of rows 
	 */
	public Screen(int columns, int rows) {
		try {
			lockKeyboard();
		} catch(KeyboardLockedException e) {
			throw new UnsupportedOperationException("Some got a interrupt during the constructor",e);
		}
		this.columns    = columns;
		this.rows       = rows;
		this.screenSize = this.columns * this.rows; 
	}


	/**
	 * Wait on the keyboard being free
	 * 
	 * @param maxWait - time in milliseconds
	 * @throws KeyboardLockedException 
	 */
	public void waitForKeyboard(int maxWait) throws TimeoutException, KeyboardLockedException {
		try {
			if (!keyboardLock.tryAcquire(maxWait, TimeUnit.MILLISECONDS)) {
				throw new TimeoutException("Wait for keyboard took longer than " + maxWait + "ms");
			}
		} catch(InterruptedException e) {
			throw new KeyboardLockedException("Unable to acquire keyboard lock", e);
		}
		keyboardLock.release();
	}

	/**
	 * Get the screen size, ie the buffer length
	 * 
	 * @return
	 */
	public int getScreenSize() {
		return this.screenSize;
	}

	/**
	 * Clear the screen and fill with nulls
	 */
	public synchronized void erase() {
		fields.clear();
		fields.add(new FieldChars((char) 0, 0, screenSize - 1));
		this.screenCursor = 0;
	}

	/**
	 * Return the buffer address of the cursor
	 * 
	 * @return - Address of the cursor
	 */
	public int getCursor() {
		return this.screenCursor;
	}

	public synchronized void processInboundMessage(Inbound3270Message inbound) throws DatastreamException {
		CommandCode commandCode = inbound.getCommandCode();
		WriteControlCharacter writeControlCharacter = inbound.getWriteControlCharacter();
		List<Order> orders = inbound.getOrders();

		if (commandCode instanceof CommandEraseWrite) {
			erase();
		}

		processOrders(orders);

		if (writeControlCharacter.isKeyboardReset()) {
			unlockKeyboard();
		}
	}

	/**
	 * Process the 3270 datastream orders to build up the screen
	 * 
	 * @param orders - List of orders
	 * @throws DatastreamException - If we discover a unknown order
	 */
	public synchronized void processOrders(Iterable<Order> orders) throws DatastreamException {
		this.workingCursor = 0;
		for(Order order : orders) {
			if (order instanceof OrderSetBufferAddress) {
				processSBA((OrderSetBufferAddress) order);
			} else if (order instanceof OrderRepeatToAddress) {
				processRA((OrderRepeatToAddress) order);
			} else if (order instanceof OrderText) {
				processText((OrderText) order);
			} else if (order instanceof OrderStartField) {
				processSF((OrderStartField) order);
			} else if (order instanceof OrderInsertCursor) {
				this.screenCursor = this.workingCursor;
			} else {
				throw new DatastreamException("Unsupported Order - " + order.getClass().getName());
			}
		}

		int pos = 0;
		//*** Merge suitable fields,  text and chars
		while(pos < this.fields.size()) {
			int nextPos = pos + 1;
			if (nextPos >= this.fields.size()) {
				break;
			}

			Field thisField = this.fields.get(pos);
			Field nextField = this.fields.get(nextPos);

			if ((thisField instanceof FieldText) 
					|| (thisField instanceof FieldChars)) {
				if ((nextField instanceof FieldText) 
						|| (nextField instanceof FieldChars)) {
					thisField.merge(this.fields, nextField);
					continue; // Go round again without incrementing position
				}
			}
			pos++;
		}

		//*** Reset the previousStartOfField variables
		//*** Find the LAST start of field
		FieldStartOfField lastStartOfField= null;
		for(int i = this.fields.size() - 1; i >= 0; i--) {
			Field field = this.fields.get(i);
			if (field instanceof FieldStartOfField) {
				lastStartOfField = (FieldStartOfField) field;
				break;
			}
		}

		//*** No SF was found,  then the whole display must be unprotected
		if (lastStartOfField == null) {
			lastStartOfField = new FieldStartOfField(0, false, false, true, false, false, false);
		}

		for(int i = 0; i < this.fields.size(); i++) {
			Field field = this.fields.get(i);
			if (field instanceof FieldStartOfField) {
				lastStartOfField = (FieldStartOfField) field;
			} else {
				field.setPreviousStartOfField(lastStartOfField);
			}
		}
	}

	/**
	 * Process a Set Buffer Address order
	 * 
	 * @param order - the order to process
	 */
	private synchronized void processSBA(OrderSetBufferAddress order) {
		this.workingCursor = order.getBufferAddress();
	}

	/**
	 * Process a Start Field order
	 * 
	 * @param order - the order to process
	 */
	private synchronized void processSF(OrderStartField order) { //NOSONAR - will be using it soon
		Field newField = new FieldStartOfField(this.workingCursor,
				order.isFieldProtected(),
				order.isFieldNumeric(),
				order.isFieldDisplay(),
				order.isFieldIntenseDisplay(),
				order.isFieldSelectorPen(),
				order.isFieldModifed());
		insertField(newField);

		this.workingCursor++;
	}

	/**
	 * Process Text - not really an order
	 * 
	 * @param order - the order to process
	 */
	private synchronized void processText(OrderText order) {		
		String text = order.getText();
		Field newField = new FieldText(text, this.workingCursor, (this.workingCursor + text.length()) - 1);
		insertField(newField);

		this.workingCursor += text.length();
	}

	/**
	 * Process the Report ot Address order 
	 * 
	 * @param order - the order to process
	 */
	private synchronized void processRA(OrderRepeatToAddress order) {
		int endOfRepeat = order.getBufferAddress();

		if (endOfRepeat <= this.workingCursor) {
			Field newField = new FieldChars(order.getChar(), this.workingCursor, this.screenSize - 1);
			insertField(newField);

			if (endOfRepeat > 0) {
				newField = new FieldChars(order.getChar(), 0, endOfRepeat);
				insertField(newField);
			}
		} else {
			Field newField = new FieldChars(order.getChar(), this.workingCursor, endOfRepeat - 1);
			insertField(newField);
		}

		this.workingCursor = endOfRepeat;
	}

	/**
	 * Insert a new field from the process orders
	 * 
	 * @param newField - The field to insert into the buffer
	 */
	public synchronized void insertField(Field newField) {
		//*** Easy if there are no pre-existing fields
		if (this.fields.isEmpty()) {
			this.fields.add(newField);
			return;
		}

		//*** Locate all the current fields that span the new start and end positions
		List<Field> selectedFields = locateFieldsBetween(newField.getStart(), newField.getEnd());
		if (selectedFields.isEmpty()) {
			//*** If there are no fields,  then find the field that is after the end address
			int followingFieldPos = this.fields.size();
			for(int i = 0; i < this.fields.size(); i++) {
				if (newField.getEnd() < this.fields.get(i).getStart()) {
					followingFieldPos = i;
					break;
				}
			}
			//*** Insert the new field into the correct position
			this.fields.add(followingFieldPos, newField);
		} else {
			//*** Tell the spanned fields to split as appropriate
			int fieldPosition = fields.indexOf(selectedFields.get(0));
			for(Field field : selectedFields) {
				field.split(this.fields, newField.getStart(), newField.getEnd());
			}

			//*** Insert at the appropriate place
			if (this.fields.size() <= fieldPosition) {
				this.fields.add(newField);
				return;
			}

			if (newField.getStart() < fields.get(fieldPosition).getStart()) {
				this.fields.add(fieldPosition, newField);
			} else {
				this.fields.add(fieldPosition + 1, newField);
			}
		}
	}

	/**
	 * Convert the fields into a string for printing or otherwise
	 * 
	 * @return - A representation of the screen
	 */
	public synchronized String printScreen() {
		StringBuilder sb = new StringBuilder();
		for(Field field : this.fields) {
			field.getFieldString(sb);
		}

		StringBuilder screenSB = new StringBuilder();
		String screenString = sb.toString();
		for(int i = 0; i < this.screenSize; i += this.columns) {
			screenSB.append(screenString.substring(i, i + this.columns));
			screenSB.append('\n');
		}
		return screenSB.toString();
	}


	/**
	 * Find fields that span the start and end addresses
	 * 
	 * @param start - The start address
	 * @param end - The end address
	 * @return - a list of covered fields
	 */
	private synchronized List<Field> locateFieldsBetween(int start, int end) {
		ArrayList<Field> selectedFields = new ArrayList<>();
		for(Field field : this.fields) {
			if (field.containsPositions(start, end)) {
				selectedFields.add(field);
			}
		}
		return selectedFields;
	}

	/**
	 * Produce a printable list of the fields on the screen 
	 * 
	 * @return - A list of the fields
	 */
	public synchronized String printFields() {
		StringBuilder sb = new StringBuilder();
		for(Field field : fields) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(field.toString());
		}
		return sb.toString();
	}

	private synchronized void lockKeyboard() throws KeyboardLockedException {
		if (!keyboardLockSet) {
			keyboardLockSet = true;
			try {
				keyboardLock.acquire();
			} catch(InterruptedException e) {
				throw new KeyboardLockedException("Unable to lock the keyboard", e);
			}
		}
	}

	private synchronized void unlockKeyboard() {
		if (keyboardLockSet) {
			keyboardLockSet = false;
			keyboardLock.release();
		}
	}

	public synchronized void positionCursorToFieldContaining(@NotNull String text) throws KeyboardLockedException, TextNotFoundException {
		if (keyboardLockSet) {
			throw new KeyboardLockedException("Unable to move cursor as keyboard is locked");
		}

		for(Field field : this.fields) {
			if (field.containsText(text)) {
				this.screenCursor = field.getStart();
				return;
			}
		}

		throw new TextNotFoundException("Unable to find a field containing '" + text + "'");		
	}

	public void searchFieldContaining(String text) throws TextNotFoundException {
		for(Field field : this.fields) {
			if (field.containsText(text)) {
				return;
			}
		}

		throw new TextNotFoundException("Unable to find a field containing '" + text + "'");		
	}



	public synchronized void tab() throws KeyboardLockedException, FieldNotFoundException {
		if (keyboardLockSet) {
			throw new KeyboardLockedException("Unable to move cursor as keyboard is locked");
		}

		int fieldPosition = 0;
		Field startField = null;
		for(; fieldPosition < this.fields.size(); fieldPosition++) {
			Field field = this.fields.get(fieldPosition);
			if (field.containsPosition(this.screenCursor)) {
				startField = field;
				break;
			}
		}

		if (startField == null) {
			throw new FieldNotFoundException("Unable to locate field to tab from, should not have happened");
		}

		while(true) {
			fieldPosition++;
			if (fieldPosition >= this.fields.size()) {
				fieldPosition = 0;
			}

			Field field = this.fields.get(fieldPosition);
			if (field.isTypeable()) {
				this.screenCursor = field.getStart();
				return;
			}

			if (field == startField) {
				throw new FieldNotFoundException("Unable to locate an unprotected field to tab to");
			}
		}

	}

	public synchronized void type(String text) throws KeyboardLockedException, FieldNotFoundException {
		if (keyboardLockSet) {
			throw new KeyboardLockedException("Unable to type as keyboard is locked");
		}

		Field typeField = null;
		int typeFieldPos = 0;
		for(int i = 0; i < this.fields.size(); i++) {
			Field field = this.fields.get(i);
			if (field.containsPosition(this.screenCursor)) {
				typeField = field;
				typeFieldPos = i;
				break;
			}
		}

		if (typeField == null) {
			throw new FieldNotFoundException("Unable to locate field where the cursor is pointing to, should not have happened");
		}

		if (!typeField.isTypeable()) {
			throw new FieldNotFoundException("Unable to type where the cursor is pointing to - " + this.screenCursor);
		}

		int length = (typeField.getEnd() - typeField.getStart()) + 1;
		if (length < text.length()) {
			throw new FieldNotFoundException("Unable to type into field as would cause a field overflow, this is not supported at the moment");
		}

		if (typeField instanceof FieldChars) {
			FieldText textField = new FieldText((FieldChars) typeField);
			this.fields.remove(typeFieldPos);
			this.fields.add(typeFieldPos, textField);
			typeField = textField;
		}

		if (!(typeField instanceof FieldText)) {
			throw new FieldNotFoundException("Unable to type into field as is not a text field, this is not supported at the moment");
		}

		((FieldText)typeField).type(text);

		this.screenCursor += text.length();
	}

	public synchronized byte[] aid(AttentionIdentification enter) throws DatastreamException, KeyboardLockedException {
		lockKeyboard();

		try {
			ByteArrayOutputStream outboundBuffer = new ByteArrayOutputStream();

			outboundBuffer.write(enter.getKeyValue());

			BufferAddress cursor = new BufferAddress(this.screenCursor);
			outboundBuffer.write(cursor.getCharRepresentation());

			for(Field field : this.fields) {
				if (field.isModified()) {
					OrderSetBufferAddress sba = new OrderSetBufferAddress(new BufferAddress(field.getStart()));
					outboundBuffer.write(sba.getCharRepresentation());

					byte[] fieldText = field.getFieldEbcdicWithoutNulls();
					outboundBuffer.write(fieldText);
				}
			}
			
			String hex = new String(Hex.encodeHex(outboundBuffer.toByteArray()));
			System.out.println("outbound=" + hex);

			return outboundBuffer.toByteArray();
		} catch(IOException e) {
			throw new DatastreamException("Unable to generate outbound datastream", e);
		}
	}

}
