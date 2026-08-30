//! The STARK engine: proves a payslip's net pay was computed correctly as
//! (sum of earnings) - (sum of deductions), without revealing the individual line items.
//! See the module-level rationale in main.rs's original doc comment history / the report
//! sent back to the user -- kept here as the reusable core the HTTP service wraps.

use winterfell::{
    crypto::{hashers::Blake3_256, DefaultRandomCoin, MerkleTree},
    math::{fields::f128::BaseElement, FieldElement, ToElements},
    matrix::ColMatrix,
    Air, AirContext, Assertion, AuxRandElements, BatchingMethod, CompositionPoly,
    CompositionPolyTrace, DefaultConstraintCommitment, DefaultConstraintEvaluator,
    DefaultTraceLde, EvaluationFrame, FieldExtension, PartitionOptions, Proof,
    ProofOptions, Prover, StarkDomain, Trace, TraceInfo, TracePolyTable,
    TraceTable, TransitionConstraintDegree,
};
use winter_prover::Deserializable;

pub const TRACE_LEN: usize = 16; // power of two; supports up to 15 line items (zero-padded)
pub const MAX_ENTRIES: usize = TRACE_LEN - 1;

#[derive(Debug)]
pub enum StarkError {
    TooManyEntries { max: usize, got: usize },
    NegativeNetPay,
    ProvingFailed(String),
    VerificationFailed(String),
    MalformedProof(String),
}

impl std::fmt::Display for StarkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StarkError::TooManyEntries { max, got } => {
                write!(f, "too many line items: got {got}, max supported is {max}")
            }
            StarkError::NegativeNetPay => write!(f, "sum of deductions exceeds sum of earnings"),
            StarkError::ProvingFailed(e) => write!(f, "proving failed: {e}"),
            StarkError::VerificationFailed(e) => write!(f, "verification failed: {e}"),
            StarkError::MalformedProof(e) => write!(f, "malformed proof bytes: {e}"),
        }
    }
}

pub struct PublicInputs {
    net_pay: BaseElement,
    num_entries: BaseElement,
}

impl ToElements<BaseElement> for PublicInputs {
    fn to_elements(&self) -> Vec<BaseElement> {
        vec![self.net_pay, self.num_entries]
    }
}

pub struct PayslipAir {
    context: AirContext<BaseElement>,
    net_pay: BaseElement,
    #[allow(dead_code)] // bound into the proof transcript via ToElements, not read in-circuit
    num_entries: BaseElement,
}

impl Air for PayslipAir {
    type BaseField = BaseElement;
    type PublicInputs = PublicInputs;

    fn new(trace_info: TraceInfo, pub_inputs: PublicInputs, options: ProofOptions) -> Self {
        assert_eq!(2, trace_info.width(), "trace must have exactly 2 columns");
        let degrees = vec![TransitionConstraintDegree::new(1)];
        let num_assertions = 2;
        PayslipAir {
            context: AirContext::new(trace_info, degrees, num_assertions, options),
            net_pay: pub_inputs.net_pay,
            num_entries: pub_inputs.num_entries,
        }
    }

    fn evaluate_transition<E: FieldElement + From<Self::BaseField>>(
        &self,
        frame: &EvaluationFrame<E>,
        _periodic_values: &[E],
        result: &mut [E],
    ) {
        let running_sum = frame.current()[0];
        let contribution = frame.current()[1];
        result[0] = frame.next()[0] - (running_sum + contribution);
    }

    fn get_assertions(&self) -> Vec<Assertion<Self::BaseField>> {
        let last_step = self.trace_length() - 1;
        vec![
            Assertion::single(0, 0, BaseElement::ZERO),
            Assertion::single(0, last_step, self.net_pay),
        ]
    }

    fn context(&self) -> &AirContext<Self::BaseField> {
        &self.context
    }
}

fn build_payslip_trace(contributions: &[BaseElement]) -> TraceTable<BaseElement> {
    assert_eq!(contributions.len(), TRACE_LEN - 1);
    let mut trace = TraceTable::new(2, TRACE_LEN);
    trace.fill(
        |state| {
            state[0] = BaseElement::ZERO;
            state[1] = contributions[0];
        },
        |step, state| {
            state[0] += state[1];
            state[1] = if step + 1 < contributions.len() {
                contributions[step + 1]
            } else {
                BaseElement::ZERO
            };
        },
    );
    trace
}

/// Builds the private witness from real earnings/deduction amounts (smallest currency unit,
/// e.g. cents). Mirrors SlipRequest.totalEarnings()/totalDeductions()/netPay() in Java,
/// just over a finite field instead of BigDecimal.
fn contributions_from_line_items(
    earnings: &[u64],
    deductions: &[u64],
) -> Result<(Vec<BaseElement>, u64), StarkError> {
    let got = earnings.len() + deductions.len();
    if got > MAX_ENTRIES {
        return Err(StarkError::TooManyEntries { max: MAX_ENTRIES, got });
    }
    let mut contributions = Vec::with_capacity(TRACE_LEN - 1);
    for &e in earnings {
        contributions.push(BaseElement::new(e as u128));
    }
    for &d in deductions {
        contributions.push(BaseElement::ZERO - BaseElement::new(d as u128));
    }
    while contributions.len() < TRACE_LEN - 1 {
        contributions.push(BaseElement::ZERO);
    }
    let total_earnings: u64 = earnings.iter().sum();
    let total_deductions: u64 = deductions.iter().sum();
    let net_pay = total_earnings
        .checked_sub(total_deductions)
        .ok_or(StarkError::NegativeNetPay)?;
    Ok((contributions, net_pay))
}

struct PayslipProver {
    options: ProofOptions,
    num_entries: BaseElement,
}

impl Prover for PayslipProver {
    type BaseField = BaseElement;
    type Air = PayslipAir;
    type Trace = TraceTable<Self::BaseField>;
    type HashFn = Blake3_256<Self::BaseField>;
    type VC = MerkleTree<Self::HashFn>;
    type RandomCoin = DefaultRandomCoin<Self::HashFn>;
    type TraceLde<E: FieldElement<BaseField = Self::BaseField>> =
        DefaultTraceLde<E, Self::HashFn, Self::VC>;
    type ConstraintCommitment<E: FieldElement<BaseField = Self::BaseField>> =
        DefaultConstraintCommitment<E, Self::HashFn, Self::VC>;
    type ConstraintEvaluator<'a, E: FieldElement<BaseField = Self::BaseField>> =
        DefaultConstraintEvaluator<'a, Self::Air, E>;

    fn get_pub_inputs(&self, trace: &Self::Trace) -> PublicInputs {
        let last_step = trace.length() - 1;
        PublicInputs { net_pay: trace.get(0, last_step), num_entries: self.num_entries }
    }

    fn options(&self) -> &ProofOptions {
        &self.options
    }

    fn new_trace_lde<E: FieldElement<BaseField = Self::BaseField>>(
        &self,
        trace_info: &TraceInfo,
        main_trace: &ColMatrix<Self::BaseField>,
        domain: &StarkDomain<Self::BaseField>,
        partition_option: PartitionOptions,
    ) -> (Self::TraceLde<E>, TracePolyTable<E>) {
        DefaultTraceLde::new(trace_info, main_trace, domain, partition_option)
    }

    fn build_constraint_commitment<E: FieldElement<BaseField = Self::BaseField>>(
        &self,
        composition_poly_trace: CompositionPolyTrace<E>,
        num_constraint_composition_columns: usize,
        domain: &StarkDomain<Self::BaseField>,
        partition_options: PartitionOptions,
    ) -> (Self::ConstraintCommitment<E>, CompositionPoly<E>) {
        DefaultConstraintCommitment::new(
            composition_poly_trace,
            num_constraint_composition_columns,
            domain,
            partition_options,
        )
    }

    fn new_evaluator<'a, E: FieldElement<BaseField = Self::BaseField>>(
        &self,
        air: &'a Self::Air,
        aux_rand_elements: Option<AuxRandElements<E>>,
        composition_coefficients: winterfell::ConstraintCompositionCoefficients<E>,
    ) -> Self::ConstraintEvaluator<'a, E> {
        DefaultConstraintEvaluator::new(air, aux_rand_elements, composition_coefficients)
    }
}

fn proof_options() -> ProofOptions {
    // Same parameters as winterfell's own documented example -- targets ~96-bit security.
    ProofOptions::new(32, 8, 0, FieldExtension::None, 8, 31, BatchingMethod::Linear, BatchingMethod::Linear)
}

/// Result of a successful prove() call: the raw proof bytes plus the public facts a caller
/// needs to store alongside them to verify later.
pub struct ProveResult {
    pub proof_bytes: Vec<u8>,
    pub net_pay_cents: u64,
    pub num_entries: usize,
}

pub fn prove(earnings_cents: &[u64], deductions_cents: &[u64]) -> Result<ProveResult, StarkError> {
    let (contributions, net_pay) = contributions_from_line_items(earnings_cents, deductions_cents)?;
    let num_entries = earnings_cents.len() + deductions_cents.len();
    let trace = build_payslip_trace(&contributions);
    let prover =
        PayslipProver { options: proof_options(), num_entries: BaseElement::new(num_entries as u128) };
    let proof = prover.prove(trace).map_err(|e| StarkError::ProvingFailed(e.to_string()))?;
    Ok(ProveResult { proof_bytes: proof.to_bytes(), net_pay_cents: net_pay, num_entries })
}

pub fn verify(proof_bytes: &[u8], net_pay_cents: u64, num_entries: usize) -> Result<(), StarkError> {
    let proof =
        Proof::read_from_bytes(proof_bytes).map_err(|e| StarkError::MalformedProof(e.to_string()))?;
    let min_opts = winterfell::AcceptableOptions::MinConjecturedSecurity(95);
    let pub_inputs = PublicInputs {
        net_pay: BaseElement::new(net_pay_cents as u128),
        num_entries: BaseElement::new(num_entries as u128),
    };
    winterfell::verify::<
        PayslipAir,
        Blake3_256<BaseElement>,
        DefaultRandomCoin<Blake3_256<BaseElement>>,
        MerkleTree<Blake3_256<BaseElement>>,
    >(proof, pub_inputs, &min_opts)
    .map_err(|e| StarkError::VerificationFailed(e.to_string()))
}
