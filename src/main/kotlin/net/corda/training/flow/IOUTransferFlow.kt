package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary = notary)
        val stateAndRef = serviceHub.vaultService.queryBy<IOUState>(
            QueryCriteria.LinearStateQueryCriteria(
                linearId = listOf(linearId)
            )
        ).states.single()
        val input = stateAndRef.state.data
        if (input.lender != ourIdentity) {
            throw IllegalArgumentException("Flow can only be run by current lender")
        }
        val output = input.withNewLender(newLender)
        val participants = input.participants.toSet() + output.participants.toSet()
        val command = Command(IOUContract.Commands.Transfer(), participants.map { it.owningKey })

        txBuilder.withItems(command, stateAndRef, StateAndContract(output, IOUContract.IOU_CONTRACT_ID))
        txBuilder.verify(serviceHub)

        val sessions = (participants - ourIdentity).map { initiateFlow(it) }
        val tx = serviceHub.signInitialTransaction(txBuilder)
        val stx = subFlow(CollectSignaturesFlow(tx, sessions))
        return subFlow(FinalityFlow(stx))
    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }

        subFlow(signedTransactionFlow)
    }
}