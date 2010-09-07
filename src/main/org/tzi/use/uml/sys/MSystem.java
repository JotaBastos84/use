/*
 * USE - UML based specification environment
 * Copyright (C) 1999-2004 Mark Richters, University of Bremen
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

// $Id$

package org.tzi.use.uml.sys;

import static org.tzi.use.util.StringUtil.inQuotes;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import javax.swing.event.EventListenerList;

import org.tzi.use.gen.tool.GGenerator;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.MOperation;
import org.tzi.use.uml.mm.MPrePostCondition;
import org.tzi.use.uml.ocl.expr.Evaluator;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.expr.VarDeclList;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.value.BooleanValue;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.ocl.value.VarBindings;
import org.tzi.use.uml.sys.events.Event;
import org.tzi.use.uml.sys.ppcHandling.PPCHandler;
import org.tzi.use.uml.sys.ppcHandling.PostConditionCheckFailedException;
import org.tzi.use.uml.sys.ppcHandling.PreConditionCheckFailedException;
import org.tzi.use.uml.sys.soil.MStatement;
import org.tzi.use.uml.sys.soil.SoilEvaluationContext;
import org.tzi.use.util.Log;
import org.tzi.use.util.StringUtil;
import org.tzi.use.util.UniqueNameGenerator;
import org.tzi.use.util.soil.StateDifference;
import org.tzi.use.util.soil.VariableEnvironment;
import org.tzi.use.util.soil.exceptions.evaluation.EvaluationFailedException;

/**
 * A system maintains a system state and provides functionality for
 * doing state transitions.
 *
 * @version     $ProjectVersion: 0.393 $
 * @author      Mark Richters 
 */
public final class MSystem {
    private MModel fModel;  // The model of this system. 
    private MSystemState fCurrentState; // The current system state. 
    private Map<String, MObject> fObjects;   // The set of all objects
    private UniqueNameGenerator fUniqueNameGenerator; // creation of object names
    protected EventListenerList fListenerList = new EventListenerList();
    private MOperationCall lastOperationCall; // last called operation (used by test suite)
    private GGenerator fGenerator;    // snapshot generator
    /** the variables of this system */
    private VariableEnvironment fVariableEnvironment;
    /** TODO */
    private PPCHandler fPPCHandlerOverride;
    /** the stack of evaluation results of statements */
    private Deque<StatementEvaluationResult> fStatementEvaluationResults;
    /** the operation-call stack */
    private Deque<MOperationCall> fCallStack;
    /** TODO */
    private Deque<MStatement> fRedoStack;
    /** TODO */
    private int fStateLock = 0;
    /** TODO */
    private Deque<MStatement> fCurrentlyEvaluatedStatements;

    private Stack<MSystemState> variationPointsStates = new Stack<MSystemState>();
    private Stack<VarBindings> variationPointsVars = new Stack<VarBindings>();
    /**
     * constructs a new MSystem
     * @param model the model of this system
     */
    public MSystem(MModel model) {
        fModel = model;
        init();
    }

    private void init() {
        fObjects = new HashMap<String, MObject>();
        fUniqueNameGenerator = new UniqueNameGenerator();
        fCurrentState = new MSystemState(fUniqueNameGenerator.generate("state#"), this);
        fGenerator = new GGenerator(this);
        fVariableEnvironment = new VariableEnvironment(fCurrentState);
        fStatementEvaluationResults = new ArrayDeque<StatementEvaluationResult>();
        fCallStack = new ArrayDeque<MOperationCall>();
        fRedoStack = new ArrayDeque<MStatement>();
        fCurrentlyEvaluatedStatements = new ArrayDeque<MStatement>();
    }

    /**
	 * Resets the system to its initial state.
	 */
	public void reset() {
	    init();
	}

	/**
     * Returns the current system state.
     */
    public MSystemState state() {
        return fCurrentState;
    }

    /**
     * Returns the system's model.
     */
    public MModel model() {
        return fModel;
    }

    /**
     * Returns the system's instance generator.
     */
    public GGenerator generator() {
        return fGenerator;
    }
    
    public VarBindings varBindings() {
		return fVariableEnvironment.constructVarBindings();
	}

	public VariableEnvironment getVariableEnvironment() {
    	return fVariableEnvironment;
    }
    
    
    public void addChangeListener(StateChangeListener l) {
        fListenerList.add(StateChangeListener.class, l);
    }
    
    public void removeChangeListener(StateChangeListener l) {
        fListenerList.remove(StateChangeListener.class, l);
    }
    
    public void registerPPCHandlerOverride(PPCHandler ppcHandlerOverride) {
    	fPPCHandlerOverride = ppcHandlerOverride;
    }
    
    /**
     * Creates and adds a new object to the system. The name of the
     * object may be null in which case a unique name is automatically
     * generated.
     *
     * @return the created object.  
     */
    MObject createObject(MClass cls, String name) throws MSystemException {
        if (cls.isAbstract() )
            throw new MSystemException("The abstract class `" + cls.name() + 
                                       "' cannot be instantiated.");
    
        // create new object and initial state
        if (name == null ) {
            name = uniqueObjectNameForClass(cls.name());
        } else if (fObjects.containsKey(name) )
            throw new MSystemException("An object with name `" + name + 
                                       "' already exists.");
        
        MObject obj = new MObjectImpl(cls, name);
        addObject(obj);
        return obj;
    }

    void addObject(MObject obj) {
        fObjects.put(obj.name(), obj);
    }

    void deleteObject(MObject obj) {
        fObjects.remove(obj.name());
    }
    
    /**
     * TODO
     * @param operationCall
     */
    private void evaluatePreConditions(MOperationCall operationCall) {
    	
    	List<MPrePostCondition> preConditions = 
			operationCall.getOperation().preConditions();
    	
    	LinkedHashMap<MPrePostCondition, Boolean> results = 
    		new LinkedHashMap<MPrePostCondition, Boolean>(preConditions.size());
    	
    	for (MPrePostCondition preCondition : preConditions) {
			Evaluator oclEvaluator = new Evaluator();
			Value evalResult = 
				oclEvaluator.eval(
						preCondition.expression(), 
						fCurrentState,
						fVariableEnvironment.constructVarBindings());
			
			boolean conditionPassed = 
				(evalResult.isDefined() && 
						evalResult.type().isBoolean() &&
						((BooleanValue)evalResult).isTrue());
			
			results.put(preCondition, conditionPassed);
    	}
    	
    	operationCall.setPreConditionsCheckResult(results);
    }
    
    
    /**
     * TODO
     * @param operationCall
     */
    private void evaluatePostConditions(MOperationCall operationCall) {
    	
    	List<MPrePostCondition> postConditions = 
			operationCall.getOperation().postConditions();
    	
    	LinkedHashMap<MPrePostCondition, Boolean> results = 
    		new LinkedHashMap<MPrePostCondition, Boolean>(postConditions.size());
    	
    	operationCall.setVarBindings(fVariableEnvironment.constructVarBindings());
    	
    	for (MPrePostCondition postCondition : postConditions) {
			Evaluator oclEvaluator = new Evaluator();
			Value evalResult = 
				oclEvaluator.eval(
						postCondition.expression(), 
						operationCall.getPreState(),
						fCurrentState,
						operationCall.getVarBindings());
			
			boolean conditionPassed = 
				(evalResult.isDefined() && 
						evalResult.type().isBoolean() &&
						((BooleanValue)evalResult).isTrue());
			
			results.put(postCondition, conditionPassed);
    	}
    	
    	operationCall.setPostConditionsCheckResult(results);
    }
    
    
    /**
     * TODO
     * @return
     */
    public MOperationCall getCurrentOperation() {
    	return fCallStack.peek();
    }
    

    /**
	 * TODO
	 * @param self
	 * @param operation
	 * @param arguments
	 * @param ppcHandler
	 * @param output
	 * @throws MSystemException 
	 */
	public void enterOperation(MOperationCall operationCall, boolean isOpenter) throws MSystemException {
						
		MOperation operation = operationCall.getOperation();
		MObject self = operationCall.getSelf();
		Value[] arguments = operationCall.getArguments();
		VarDeclList parameters = operation.paramList();
		
		// check parameters
		
		int numArguments = arguments.length;
		int numParameters = parameters.size();
		
		if (numArguments != numParameters) {
			throw new MSystemException(
					"Number of arguments does not match declaration of " +
					" operation " +
					StringUtil.inQuotes(operation.name()) +
					" in class " +
					StringUtil.inQuotes(self.cls().name()) +
					". Expected " +
					numParameters +
					" arguments" + ((numArguments == 1) ? "" : "s") +
					", found " +
					numArguments +
					".");
		}
		
		for (int i = 0; i < numParameters; ++i) {
			
			VarDecl parameter = parameters.varDecl(i);
			Value argument = arguments[i];
			
			Type expectedType = parameter.type();
			Type foundType = argument.type();
			
			if (!foundType.isSubtypeOf(expectedType)) {
	
				throw new MSystemException(
						"Type mismatch in argument " +
						i +
						". Expected type " +
						StringUtil.inQuotes(expectedType) +
						", found " +
						StringUtil.inQuotes(foundType) +
						".");
			}				
		}
		
		// set up variable environment
		fVariableEnvironment.pushFrame(isOpenter);
		fVariableEnvironment.assign("self", self.value());
		for (int i = 0; i < parameters.size();++i) {
			fVariableEnvironment.assign(parameters.varDecl(i).name(), arguments[i]);
		}
		
		// make sure we have a ppc handler
		PPCHandler ppcHandler;
		if (fPPCHandlerOverride != null) {
			ppcHandler = fPPCHandlerOverride;
		} else if (operationCall.hasPreferredPPCHandler()) {
			ppcHandler = operationCall.getPreferredPPCHandler();
		} else {
			ppcHandler = operationCall.getDefaultPPCHandler();
		}
		
		// check pre conditions
		
		MStatement currentStatement = getCurrentStatement();
		if (currentStatement != null) {
			if (!stateIsLocked()) {
				currentStatement.enteredOperationDuringEvaluation(
						operationCall);
			}
		}
		
		fCallStack.push(operationCall);
		evaluatePreConditions(operationCall);
		lockState();
		try {
			ppcHandler.handlePreConditions(this, operationCall);
		} catch (PreConditionCheckFailedException e) {
			fCallStack.pop();
			fVariableEnvironment.popFrame();
			throw new MSystemException(e.getMessage(), e);
		} finally {
			unlockState();
		}
		
		// if the post conditions of this operations require a pre state 
		// require a state copy, create it
		if (operationCall.hasPostConditions()
			    && operationCall.getOperation().postConditionsRequirePreState()) {
			
			operationCall.setPreState(
					new MSystemState(
							fUniqueNameGenerator.generate("state#"), 
							fCurrentState));
		} else {
			operationCall.setPreState(fCurrentState);
		}
		
		operationCall.setEnteredSuccessfully(true);
	}


	/**
	 * TODO
	 * @param resultValue
	 * @param ppcHandler
	 * @param force
	 * @return
	 * @throws MSystemException
	 */
	public MOperationCall exitOperation(
			Value resultValue,
			boolean forceExit) throws MSystemException {
		
		/////
		// 1. Determine which operation is to be exited
		
		MOperationCall operationCall = getCurrentOperation();
		
		if (operationCall == null) {
			throw new MSystemException("Call stack is empty.");
		}
		
		/////
		// 2. was the operation call successful? If not, we're
		//    not interested in the result value
		
		if (operationCall.executionHasFailed()) {
			exitCurrentOperation();
			
			return operationCall;
		}
		
		MOperation operation = operationCall.getOperation();
		
		/////
		// 3. Handle return value
		//    If the operation requires a return value, we need
		//    a value of a compatible type. If none was supplied
		//    to this method, the operation is not exited, unless
		//    the forceExit flag was set.
		
		// operation has a return value
		if (operation.hasResultType()) {
			// result value is missing
			if (resultValue == null) {
				
				if (forceExit) {
					exitCurrentOperation();
				}
	        	
				throw new MSystemException(
	            		"Result value of type " +
	            		inQuotes(operation.resultType()) +
	            		" required on exit of operation " +
	            		inQuotes(operation) +
	            		"." + 
	            		(forceExit ? "" : " Operation is still active."));
				
			// result value has incompatible type
	        } else if (!resultValue.type().isSubtypeOf(operation.resultType())) {
	        	
	        	if (forceExit) {
					exitCurrentOperation();
				}
	        	
	        	throw new MSystemException(
	        			"Result value type " +
	        			inQuotes(resultValue.type()) +
	        			" does not match operation result type " +
	        			inQuotes(operation.resultType()) +
	        			"." +
	        			(forceExit ? "" : " Operation is still active."));
	        // result value is of correct type
	        } else {
	        	fVariableEnvironment.assign("result", resultValue);
	        	operationCall.setResultValue(resultValue);	
	        }
		// operation has no return value
	    } else {
	    	// redundant result value, just give a warning
	    	if (resultValue != null) {
	    		Log.out().println(
	    				"Warning: Result value " + 
	    				inQuotes(resultValue) + 
	    				" is ignored, since operation " + 
	    				inQuotes(operation) +
	    				" is not defined to return a value.");
	    	}
	    }
		
		/////
		// 4. Handle post conditions
		//    The operation's post conditions are evaluated and the 
		//    results are inspected by a PPC handler. Even if not all
		//    post conditions hold, the operation is exited.
		
		// make sure we have a ppc handler
		PPCHandler ppcHandler;
		if (fPPCHandlerOverride != null) {
			ppcHandler = fPPCHandlerOverride;
		} else if (operationCall.hasPreferredPPCHandler()) {
			ppcHandler = operationCall.getPreferredPPCHandler();
		} else {
			ppcHandler = operationCall.getDefaultPPCHandler();
		}
		
		evaluatePostConditions(operationCall);
		
		try {
			ppcHandler.handlePostConditions(this, operationCall);
		} catch (PostConditionCheckFailedException e) {
			throw(new MSystemException(e.getMessage()));
		} finally {
			exitCurrentOperation();
		}
		
		operationCall.setExitedSuccessfully(true);
		
		return operationCall;
	}
	
	
	/**
	 * TODO
	 */
	private void exitCurrentOperation() {
		MOperationCall currentOperation = getCurrentOperation();
		currentOperation.setExited(true);
		MStatement currentStatement = getCurrentStatement();
		if (currentStatement != null && !stateIsLocked()) {
			currentStatement.exitedOperationDuringEvaluation(
					getCurrentOperation());
		}
		fCallStack.pop();
		fVariableEnvironment.popFrame();
	}
	
	
	/**
     * Returns a unique name that can be used for a new object of the
     * given class.  
     */
    public String uniqueObjectNameForClass(String clsName) {
        return fUniqueNameGenerator.generate(clsName);
    }
    
    
    /**
     * TODO
     * @param statement
     * @param undoOnFailure
     * @param storeResult
     * @return
     * @throws MSystemException
     */
    private StatementEvaluationResult evaluate(
			MStatement statement,
			boolean undoOnFailure,
			boolean storeResult) throws MSystemException {
    	
    	return evaluate(
    			statement, 
    			new SoilEvaluationContext(this),
    			undoOnFailure, 
    			storeResult);
    }
    
   
    /**
	 * TODO
	 * @param statement
	 * @throws EvaluationFailedException
	 */
	private StatementEvaluationResult evaluate(
			MStatement statement,
			SoilEvaluationContext context,
			boolean undoOnFailure,
			boolean storeResult) throws MSystemException {
		
		if (stateIsLocked()) {
			throw new MSystemException(
					"The system currently cannot be modified.");
		}
		
		fCurrentlyEvaluatedStatements.push(statement);
		
		if (context.isUndo()) {
			fUniqueNameGenerator.popState();
		} else {
			fUniqueNameGenerator.pushState();
		}
		
		StatementEvaluationResult result = statement.evaluate(context);
		
		//fVariableEnvironment.setObjectVariables(fCurrentState.allObjects());
		
		fCurrentlyEvaluatedStatements.pop();
		
		if (storeResult) {
			fStatementEvaluationResults.push(result);
		}
		
		fireStateChanged(result.getStateDifference());
		
		if (!result.wasSuccessfull()) {
			if (undoOnFailure) {
				fStatementEvaluationResults.pop();
				evaluate(result.getInverseStatement(), false, false);
			}
			
			throw new MSystemException(
					result.getException().getMessage(),
					result.getException());
		}
		
		return result;
	}

	/**
	 * TODO
	 * @param differences
	 */
	private void fireStateChanged(StateDifference differences) {
		Object[] listeners = fListenerList.getListenerList();
		for (int i = listeners.length-2; i >= 0; i -= 2) {
	        if (listeners[i] == StateChangeListener.class) {
	        	StateChangeEvent sce = new StateChangeEvent(this);
	        	differences.fillStateChangeEvent(sce);
	            ((StateChangeListener)listeners[i+1]).stateChanged(sce);
	        }          
	    }
		
		differences.clear();
	}

	/**
     * TODO
     * @param statement
     * @throws EvaluationFailedException
     */
    public StatementEvaluationResult evaluateStatement(
    		MStatement statement) throws MSystemException {
    	    	   	
    	return evaluateStatement(statement, true, true);
    }
    
    
    /**
     * TODO
     * @param statement
     * @param undoOnFailure
     * @param storeResult
     * @return
     * @throws EvaluationFailedException
     */
    public StatementEvaluationResult evaluateStatement(
    		MStatement statement,
    		boolean undoOnFailure,
    		boolean storeResult) throws MSystemException {
    	
    	fRedoStack.clear();
    	
    	Log.trace(this, "evaluating " + statement.getShellCommand());
    	StatementEvaluationResult result = 
    		evaluate(statement, undoOnFailure, storeResult);
    	
    	return result;
    }
    
    
    public Value evaluateStatementInExpression(
    		MStatement statement) throws MSystemException {
    	
    	MStatement currentStatement = getCurrentStatement();
    	
    	if (currentStatement == null) {
    		evaluate(statement, false, false);
    	} else {
    		try {
    			currentStatement.evaluateSubStatement(statement);
    		} catch (EvaluationFailedException e) {
    			throw new MSystemException(e.getMessage(), e);
    		}
    	}
    	
		return fVariableEnvironment.lookUp("result");
    }
    
    
    /**
     * TODO
     * @return
     * @throws MSystemException
     * @throws EvaluationFailedException
     */
    public StatementEvaluationResult undoLastStatement() 
    throws MSystemException {
    	
    	if (fStatementEvaluationResults.isEmpty()) {
    		throw new MSystemException("nothing to undo");
    	}
    	
    	StatementEvaluationResult lastResult = 
    		fStatementEvaluationResults.pop();
    	
    	MStatement lastStatement = lastResult.getEvaluatedStatement();
    	MStatement inverseStatement = lastResult.getInverseStatement();
    	
    	fRedoStack.push(lastStatement);
    	Log.trace(this, "undoing a statement");
    	
    	SoilEvaluationContext context = new SoilEvaluationContext(this);
    	context.setIsUndo(true);
    	
    	return evaluate(inverseStatement, context, false, false);
    }
    
    
    /**
	 * TODO
	 * @throws MSystemException
	 * @throws EvaluationFailedException
	 */
	public StatementEvaluationResult redoStatement() throws MSystemException {
		
		if (fRedoStack.isEmpty()) {
			throw new MSystemException("nothing to redo");
		}
		
		MStatement redoStatement = fRedoStack.pop();
		
		Log.trace(this, "redoing a statement");
		
		SoilEvaluationContext context = new SoilEvaluationContext(this);
		context.setIsRedo(true);
		
		StatementEvaluationResult result = 
			evaluate(
					redoStatement, 
					context, 
					false, 
					true);
		
		return result;
	}

//        lastOperationCall = opcall;
	/**
     * TODO
     * @return
     */
    public String getUndoDescription() {
    	if (fStatementEvaluationResults.isEmpty()) {
    		return null;
    	} else {
    		StatementEvaluationResult lastResult = 
    			fStatementEvaluationResults.peek();
    		
    		MStatement lastEvaluatedStatement = 
    			lastResult.getEvaluatedStatement();
    		
    		return lastEvaluatedStatement.getShellCommand();
    	}
    }
    
    
    public MStatement nextToRedo() {
    	return fRedoStack.peek();
    }
    
    
    public MOperationCall getLastOperationCall() {
    	return lastOperationCall;
    }
    
    /**
     * TODO
     * @return
     */
    public String getRedoDescription() {
    	
    	if (fRedoStack.isEmpty()) {
    		return null;
    	}
    	
    	return fRedoStack.peek().getShellCommand();
    }

	
	/**
     * TODO
     * @param out
     */
    public void writeSoilStatements(PrintWriter out) {
    	for (MStatement statement : getEvaluatedStatements()) {
    		out.println(statement.getShellCommand());
    	}
    }

    /**
     * TODO
     * @return
     */
    public int numEvaluatedStatements() {
    	return fStatementEvaluationResults.size();
    }
    
    /**
     * TODO
     * @return
     */
    public List<MStatement> getEvaluatedStatements() {
    	List<MStatement> evaluatedStatements = 
    		new ArrayList<MStatement>(fStatementEvaluationResults.size());
    	
    	for (StatementEvaluationResult result : fStatementEvaluationResults) {
    		evaluatedStatements.add(0, result.getEvaluatedStatement());
    	}
    	
    	return evaluatedStatements;
    }
    
    
    /**
     * TODO
     * @return
     */
    private MStatement getCurrentStatement() {
    	return fCurrentlyEvaluatedStatements.peek();
    }
    
    
    /**
	 * TODO
	 * @return
	 */
	public Deque<MOperationCall> getCallStack() {
		return fCallStack;
	}
	
	
	/**
	 * TODO
	 * @param object
	 * @return
	 */
	public boolean hasActiveOperation(MObject object) {
		
		for (MOperationCall operationCAll : fCallStack) {
			if (operationCAll.getSelf() == object) {
				return true;
			}
		}
		
		return false;
	}
	

	/**
     * TODO
     * @return
     */
    public List<Event> getAllEvents() {
    	List<Event> result = new ArrayList<Event>();
    	
    	Iterator<StatementEvaluationResult> it = 
    		fStatementEvaluationResults.descendingIterator();
    	while (it.hasNext()) {
    		result.addAll(it.next().getEvents());
    	}
    	
    	MStatement currentStatement = fCurrentlyEvaluatedStatements.peek();
    	if (currentStatement != null) {
    		result.addAll(currentStatement.getResult().getEvents());
    	}
    	
    	return result;
    }
    
    private void lockState() {
    	++fStateLock;
    }
    
    private void unlockState() {
    	--fStateLock;
    }
    
    private boolean stateIsLocked() {
    	return fStateLock > 0;
    }
    
    public void updateListeners() {
    	MStatement currentStatement = fCurrentlyEvaluatedStatements.peek();
    	
    	if (currentStatement != null) {
    		fireStateChanged(
					currentStatement.getResult().getStateDifference());
		}
    }
    
    /**
     * Starts a new variation in a test case
     */
    public void beginVariation() {
		/*
    	// Store current system state on stack
		variationPointsStates.push(this.fCurrentState);
		variationPointsVars.push(this.fVarBindings);
		
		this.fCurrentState = new MSystemState(new UniqueNameGenerator().generate("variation#"), this.fCurrentState);
		this.fVarBindings = new VarBindings(fVarBindings);
		*/
	}
	
    /**
     * Ends a variation in a test case
     */
	public void endVariation() throws MSystemException {
		/*
		if (variationPointsStates.isEmpty()) {
			throw new MSystemException("No Variation to end!");
		}
		
		this.fCurrentState = variationPointsStates.pop();
		this.fVarBindings = variationPointsVars.pop();
		*/
	}
}
