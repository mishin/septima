/**
 * 
 * @author mg
 * @name bad_schema
 */
SELECT T0.ORDER_NO, 'Some text', TABLE1.ID, TABLE1.F1, TABLE1.F3, T0.AMOUNT
FROM TABLE1, TABLE2, #entities.inline.subQuery T0
WHERE ((TABLE2.FIELDA < TABLE1.F1) AND (:P2 = TABLE1.F3)) AND (:P3 = T0.AMOUNT)
