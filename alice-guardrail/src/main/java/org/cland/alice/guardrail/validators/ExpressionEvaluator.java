package org.cland.alice.guardrail.validators;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/**
 * 安全的精确算术表达式求值器 —— 数值结果交叉校验的「第二把尺子」。
 *
 * <p><b>目的（承 `base/cland-crawler#528` / `#530`）：</b>独立于被测工具，用 {@link BigDecimal}
 * 精确复算算术表达式，从而识别「工具成功返回、数值却悄悄错」的静默精度缺陷（典型：浮点实现下 &gt;2^53 的整数运算丢精度）。
 *
 * <p><b>支持的语法（白名单，其余一律抛异常）：</b>
 *
 * <ul>
 *   <li>十进制数字字面量（可含小数点与科学计数法，如 {@code 1.5e3}）
 *   <li>二元运算符：{@code + - * / // % ** ^}（{@code **} 与 {@code ^} 均为幂；{@code //} 为向下取整除法； {@code %} 为
 *       Python 语义取模）
 *   <li>一元运算符：{@code + -}
 *   <li>括号分组
 * </ul>
 *
 * <p><b>安全边界：</b>不解析函数、变量、属性访问等；幂运算限制指数绝对值（{@link #MAX_POWER_EXPONENT}）与 结果有效位数（{@link
 * #MAX_RESULT_PRECISION}），防止表达式被用作资源放大器。
 *
 * <p><b>调用约定：</b>无法求值（语法不支持/超限）时抛 {@link IllegalArgumentException}，调用方应视为「无法交叉校验」
 * 而非「结果错误」——不能用「复算失败」去误判工具。
 */
final class ExpressionEvaluator {

  /** 除法的精度上下文（无法整除时保留 DECIMAL128 精度）。 */
  private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;

  /** 幂运算指数绝对值上限。 */
  private static final int MAX_POWER_EXPONENT = 1000;

  /** 幂运算结果有效位数上限（防资源放大）。 */
  private static final int MAX_RESULT_PRECISION = 100_000;

  private final String src;
  private int pos;

  private ExpressionEvaluator(String src) {
    this.src = src;
  }

  /**
   * 精确求值一个算术表达式。
   *
   * @param expression 算术表达式（如 {@code (12+8)*3}）
   * @return 精确求值结果
   * @throws IllegalArgumentException 表达式为 null、语法不支持或超出安全边界时
   */
  static BigDecimal evaluate(String expression) {
    Objects.requireNonNull(expression, "expression must not be null");
    ExpressionEvaluator parser = new ExpressionEvaluator(expression);
    BigDecimal value = parser.parseExpr();
    parser.skipWhitespace();
    if (parser.pos < parser.src.length()) {
      throw new IllegalArgumentException(
          "unexpected token at position "
              + parser.pos
              + ": '"
              + parser.src.substring(parser.pos)
              + "'");
    }
    return value;
  }

  // ========================================================================
  // 递归下降解析（Python 优先级：幂 > 一元 > 乘除 > 加减）
  // ========================================================================

  private BigDecimal parseExpr() {
    BigDecimal value = parseTerm();
    while (true) {
      skipWhitespace();
      if (consume('+')) {
        value = value.add(parseTerm());
      } else if (consume('-')) {
        value = value.subtract(parseTerm());
      } else {
        return value;
      }
    }
  }

  private BigDecimal parseTerm() {
    BigDecimal value = parseFactor();
    while (true) {
      skipWhitespace();
      if (consume("//")) {
        value = floorDivide(value, parseFactor());
      } else if (consume('*')) {
        value = value.multiply(parseFactor());
      } else if (consume('/')) {
        value = value.divide(parseFactor(), DIVISION_CONTEXT);
      } else if (consume('%')) {
        value = pythonMod(value, parseFactor());
      } else {
        return value;
      }
    }
  }

  private BigDecimal parseFactor() {
    skipWhitespace();
    if (consume('+')) {
      return parseFactor();
    }
    if (consume('-')) {
      return parseFactor().negate();
    }
    return parsePower();
  }

  private BigDecimal parsePower() {
    BigDecimal base = parseAtom();
    skipWhitespace();
    if (consume("**") || consume('^')) {
      // 右结合，且指数可带一元符号（Python 语义）
      return pow(base, parseFactor());
    }
    return base;
  }

  private BigDecimal parseAtom() {
    skipWhitespace();
    if (consume('(')) {
      BigDecimal value = parseExpr();
      skipWhitespace();
      if (!consume(')')) {
        throw new IllegalArgumentException("missing closing parenthesis at position " + pos);
      }
      return value;
    }
    return parseNumber();
  }

  private BigDecimal parseNumber() {
    int start = pos;
    while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
      pos++;
    }
    if (pos < src.length() && src.charAt(pos) == '.') {
      pos++;
      while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
        pos++;
      }
    }
    if (pos == start || (pos == start + 1 && src.charAt(start) == '.')) {
      throw new IllegalArgumentException("expected number at position " + start);
    }
    // 可选科学计数法指数
    if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
      int save = pos;
      pos++;
      if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
        pos++;
      }
      int digitsStart = pos;
      while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
        pos++;
      }
      if (pos == digitsStart) {
        pos = save; // 不是合法指数，回退（让上层按未消费处理）
      }
    }
    return new BigDecimal(src.substring(start, pos));
  }

  // ========================================================================
  // 运算符语义
  // ========================================================================

  /** Python 语义的向下取整除法（向 -∞ 取整，而非向 0 截断）。 */
  private static BigDecimal floorDivide(BigDecimal dividend, BigDecimal divisor) {
    if (divisor.signum() == 0) {
      throw new IllegalArgumentException("division by zero");
    }
    BigDecimal quotient = dividend.divideToIntegralValue(divisor);
    BigDecimal remainder = dividend.subtract(quotient.multiply(divisor));
    if (remainder.signum() != 0 && remainder.signum() != divisor.signum()) {
      quotient = quotient.subtract(BigDecimal.ONE);
    }
    return quotient;
  }

  /** Python 语义取模（余数符号跟随除数）。 */
  private static BigDecimal pythonMod(BigDecimal dividend, BigDecimal divisor) {
    if (divisor.signum() == 0) {
      throw new IllegalArgumentException("modulo by zero");
    }
    BigDecimal remainder = dividend.remainder(divisor);
    if (remainder.signum() != 0 && remainder.signum() != divisor.signum()) {
      remainder = remainder.add(divisor);
    }
    return remainder;
  }

  /** 幂运算：指数必须为整数且在安全边界内。 */
  private static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
    BigDecimal normalized = exponent.stripTrailingZeros();
    if (normalized.scale() > 0) {
      throw new IllegalArgumentException("non-integer exponent is not supported: " + exponent);
    }
    BigInteger exp = normalized.toBigIntegerExact();
    if (exp.abs().compareTo(BigInteger.valueOf(MAX_POWER_EXPONENT)) > 0) {
      throw new IllegalArgumentException("exponent out of range: " + exponent);
    }
    if (exp.signum() < 0) {
      BigDecimal denominator = base.pow(exp.negate().intValueExact());
      if (denominator.signum() == 0) {
        throw new IllegalArgumentException("zero cannot be raised to a negative power");
      }
      return BigDecimal.ONE.divide(denominator, DIVISION_CONTEXT);
    }
    BigDecimal result = base.pow(exp.intValueExact());
    if (result.precision() > MAX_RESULT_PRECISION) {
      throw new IllegalArgumentException("result precision out of range");
    }
    return result;
  }

  // ========================================================================
  // 词法工具
  // ========================================================================

  private void skipWhitespace() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
      pos++;
    }
  }

  private boolean consume(char expected) {
    if (pos < src.length() && src.charAt(pos) == expected) {
      pos++;
      return true;
    }
    return false;
  }

  private boolean consume(String expected) {
    if (src.startsWith(expected, pos)) {
      pos += expected.length();
      return true;
    }
    return false;
  }
}
