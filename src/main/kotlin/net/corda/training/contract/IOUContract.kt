package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.utils.sumCash
import net.corda.training.state.IOUState

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Looks at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        // Add commands here.
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value) {
            is Commands.Issue -> {
                requireThat {
                    "No inputs should be consumed when issuing an IOU." using (tx.inputs.size == 0)
                    "Only one output state should be created when issuing an IOU." using (tx.outputs.size == 1)

                    val output = tx.outputStates.single() as IOUState
                    "A newly issued IOU must have a positive amount." using (output.amount.quantity > 0)
                    "The lender and borrower cannot have the same identity." using (output.lender != output.borrower)

                    "Both lender and borrower together only may sign IOU issue transaction." using (
                                command.signers.toSet() == output.participants.map { it.owningKey }.toSet())
                }
            }
            is Commands.Transfer -> {
                requireThat {
                    "An IOU transfer transaction should only consume one input state." using (tx.inputs.size == 1)
                    "An IOU transfer transaction should only create one output state." using (tx.outputs.size == 1)

                    val input = tx.inputStates.single() as IOUState
                    val output = tx.outputStates.single() as IOUState

                    "Only the lender property may change." using (input == output.copy(lender=input.lender))
                    "The lender property must change in a transfer." using (input.lender != output.lender)

                    "The borrower, old lender and new lender only must sign an IOU transfer transaction" using (
                            command.signers.toSet() == input.participants.map { it.owningKey }.toSet() `union`
                                    output.participants.map { it.owningKey }.toSet()
                            )
                }
            }
            is Commands.Settle -> {
                val ious = tx.groupStates<IOUState, UniqueIdentifier> { it.linearId }.single()
                requireThat {
                    "There must be one input IOU." using (ious.inputs.size == 1)
                    val input = tx.inputsOfType<IOUState>().single()
                    val outputCache = tx.outputsOfType<Cash.State>()
                    "There must be output cash." using (outputCache.isNotEmpty())

                    val paidToLenderStates = outputCache.filter { it.owner == input.lender}
                    "There must be output cash paid to the recipient." using (paidToLenderStates.isNotEmpty())

                    val paidToLender = paidToLenderStates.sumCash().withoutIssuer()
                    "The amount settled cannot be more than the amount outstanding." using (input.amount - input.paid >= paidToLender)

                    val paidTotal = input.paid + paidToLender
                    val outputIOUs = tx.outputsOfType<IOUState>()
                    if (paidTotal < input.amount) {
                        "There must be one output IOU." using (outputIOUs.isNotEmpty())
                        val output = outputIOUs.single()
                        "The borrower may not change when settling." using (output.borrower == input.borrower)
                        "The amount may not change when settling." using (output.amount == input.amount)
                        "The lender may not change when settling." using (output.lender == input.lender)

                    } else {
                        "There must be no output IOU as it has been fully settled." using (outputIOUs.isEmpty())
                    }
                    "Both lender and borrower together only must sign IOU settle transaction." using (
                            command.signers.toSet() == input.participants.map { it.owningKey }.toSet())
                }
            }
            else -> {
                throw IllegalArgumentException("Required net.corda.training.contract.IOUContract.Commands command")
            }
        }
    }
}
