/**
 * 
 * @author mg
 */
SELECT *
FROM
    TABLE1, TABLE2, #entities.inline.emptyQuery T0
WHERE (TABLE2.FIELDA < TABLE1.F1) AND (:P2 = TABLE1.F3) AND (:P3 = T0.AMOUNT)
